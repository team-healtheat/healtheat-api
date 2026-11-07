package com.healtheat.healtheat_api.chat;

import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.mealrecord.MealHistoryQueryService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ChatService {
    private final EntityManager entities;
    private final ChatModel model;
    private final HealthProfileService health;
    private final PreferenceService preferences;
    private final MealHistoryQueryService history;
    ChatService(EntityManager entities,ChatModel model,HealthProfileService health,PreferenceService preferences,
                MealHistoryQueryService history) {
        this.entities=entities; this.model=model; this.health=health; this.preferences=preferences; this.history=history;
    }
    @Transactional
    public ChatDtos.Response send(long userId,ChatDtos.MessageRequest request) {
        if (entities.find(User.class,userId)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        ChatSession session=request.sessionId()==null ? newSession(userId) : session(userId,request.sessionId());
        if (request.sessionId()!=null) {
            entities.lock(session,LockModeType.PESSIMISTIC_WRITE);
            ensureActive(session);
        }
        var profile=health.get(userId);
        var factors=profile.factors().stream().map(f -> new ChatModel.Factor(f.factorId(),f.name(),f.type(),f.reportedStatus(),f.active())).toList();
        var recentTurns=conversation(session.id);
        var classification=model.classify(request.message(),factors,recentTurns);
        if (classification==null || classification.intent()==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Chat intent response was invalid");
        validateClassification(classification);
        HealthDtos.ProfileResponse updatedHealthProfile=null;
        com.healtheat.healtheat_api.mealplan.MealPlanDtos.Plan updatedMealPlan=null;
        String reply;
        switch (classification.intent()) {
            case GET_MEAL_HISTORY -> reply=historyReply(userId,classification);
            case GET_HEALTH_PROFILE -> reply=profileReply(profile);
            case OUT_OF_SCOPE -> reply="저는 음식과 식습관 상담, 식사 기록 조회를 도와드릴 수 있어요. 건강정보 변경은 마이페이지에서, 식단 재추천은 식단 화면에서 해 주세요.";
            case GENERAL_CHAT -> reply="안녕하세요! 음식과 식습관 상담과 식사 기록 조회를 도와드릴게요.";
            case ADD_HEALTH_FACTOR, UPDATE_HEALTH_FACTOR, REMOVE_HEALTH_FACTOR -> {
                reply="건강정보는 마이페이지에서 직접 변경해 주세요.";
            }
            case REGENERATE_MEAL -> {
                reply="식단 재추천은 식단 화면에서 해 주세요.";
            }
            default -> {
                int requested=classification.periodDays()==null ? 14 : classification.periodDays();
                if (requested<1) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Chat period was invalid");
                var pref=preferences.get(userId);
                var context=new ChatModel.AdviceContext(request.message(),factors.stream().filter(ChatModel.Factor::active).toList(),pref.allergyStatus(),pref.allergens(),
                    history.recentWithinDays(userId,requested),Math.min(requested,30),requested>30,recentTurns);
                reply=model.answer(context);
                if (reply==null || reply.isBlank() || reply.length()>12000) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Chat response was invalid");
                if (requested>30) reply="식사 기록은 최근 30일까지만 확인할 수 있어요. "+reply;
            }
        }
        session=session(userId,session.id);
        entities.persist(new ChatMessage(session.id,"USER",request.message().trim()));
        entities.persist(new ChatMessage(session.id,"ASSISTANT",reply));
        session.updatedAt=Instant.now();
        return new ChatDtos.Response(session.id,reply,classification.intent().name(),updatedHealthProfile,updatedMealPlan);
    }
    @Transactional
    public ChatDtos.Session start(long userId) {
        lockUser(userId);
        var active=entities.createQuery("select s from ChatSession s where s.userId = :userId and s.createdAt > :cutoff order by s.createdAt desc, s.id desc",ChatSession.class)
            .setParameter("userId",userId).setParameter("cutoff",Instant.now().minus(java.time.Duration.ofHours(24))).setMaxResults(1).getResultList();
        return sessionDto(active.isEmpty() ? newSession(userId) : active.get(0));
    }
    @Transactional
    public void end(long userId,long sessionId) {
        var s=session(userId,sessionId);
        entities.lock(s,LockModeType.PESSIMISTIC_WRITE);
        ensureActive(s);
        deleteSession(s);
    }
    @Transactional
    public void deleteExpired() {
        var expired=entities.createQuery("select s from ChatSession s where s.createdAt <= :cutoff order by s.id",ChatSession.class)
            .setParameter("cutoff",Instant.now().minus(java.time.Duration.ofHours(24)))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (var s:expired) deleteSession(s);
    }
    private void deleteSession(ChatSession s) {
        entities.createQuery("delete from ChatMessage m where m.sessionId = :id").setParameter("id",s.id).executeUpdate();
        entities.remove(s);
    }
    private ChatDtos.Session sessionDto(ChatSession s) {
        return new ChatDtos.Session(s.id,s.createdAt,s.updatedAt,s.createdAt.plus(java.time.Duration.ofHours(24)));
    }
    @Transactional(readOnly=true)
    public List<ChatDtos.Session> sessions(long userId) {
        return entities.createQuery("select s from ChatSession s where s.userId = :userId and s.createdAt > :cutoff order by s.updatedAt desc, s.id desc",ChatSession.class)
            .setParameter("userId",userId).setParameter("cutoff",Instant.now().minus(java.time.Duration.ofHours(24))).getResultList().stream().map(this::sessionDto).toList();
    }
    @Transactional(readOnly=true)
    public List<ChatDtos.Message> messages(long userId,long sessionId) {
        session(userId,sessionId);
        return entities.createQuery("select m from ChatMessage m where m.sessionId = :id order by m.id",ChatMessage.class)
            .setParameter("id",sessionId).getResultList().stream().map(m -> new ChatDtos.Message(m.id,m.role,m.content,m.createdAt)).toList();
    }
    private void validateClassification(ChatModel.Classification c) {
        if ((c.foodName()!=null && c.foodName().length()>200) || (c.additionalRequest()!=null && c.additionalRequest().length()>1000)
            || (c.mealType()!=null && c.mealType().length()>30) || (c.targetFactorId()!=null && c.targetFactorId()<=0))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Chat intent response was invalid");
    }
    private String historyReply(long userId,ChatModel.Classification c) {
        int days=c.periodDays()==null ? 14 : c.periodDays();
        if (days<1) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Chat period was invalid");
        if (c.foodName()==null || c.foodName().isBlank()) {
            var records=history.recentWithinDays(userId,days);
            if (records.isEmpty()) return "최근 "+Math.min(days,30)+"일 동안 저장된 식사 기록이 없어요. 기록하지 않은 식사는 확인할 수 없어요.";
            String text=String.join("; ",records.stream().map(r -> r.mealDate()+" "+mealLabel(r.mealType())+": "+String.join(", ",r.foods())).toList());
            return (days>30 ? "식사 기록은 최근 30일까지만 보관해요. " : "")+"저장된 식사 기록이에요: "+text;
        }
        var count=history.countFood(userId,c.foodName(),days);
        if(count.count()==0) {
            var matches=history.findFoodMatches(userId,c.foodName(),days);
            if(matches.size()==1) return "최근 "+Math.min(days,30)+"일 기록에서 '"+matches.get(0).foodName()+"'을(를) 찾았어요. 이 음식으로 기록된 횟수는 "+matches.get(0).count()+"회예요.";
            if(matches.size()>1) return "비슷한 음식이 여러 개 있어요: "+String.join(", ",matches.stream().limit(10).map(MealHistoryQueryService.FoodMatch::foodName).toList())+". 어떤 음식을 찾으시나요?";
            return "최근 "+Math.min(days,30)+"일 기록에서 '"+c.foodName()+"'과 일치하는 음식 이름을 찾지 못했어요. 다른 이름으로 기록했을 수 있어요.";
        }
        return (count.limitedToRetention() ? "식사 기록은 최근 30일까지만 보관해요. " : "")+
            "최근 "+Math.min(days,30)+"일 동안 "+count.foodName()+"을(를) 먹었다고 기록한 횟수는 "+count.count()+"회예요. 기록하지 않은 식사는 포함되지 않아요.";
    }
    private String profileReply(HealthDtos.ProfileResponse profile) {
        var active=profile.factors().stream().filter(HealthDtos.ProfileFactor::active).toList();
        if (active.isEmpty()) return "등록된 건강 정보가 없어요.";
        return "현재 등록된 건강 정보예요: "+String.join(", ",active.stream().map(f -> f.name()+" ("+healthTypeLabel(f.type())+")").toList());
    }
    private String healthTypeLabel(String type) {
        if (type==null) return "기록";
        return switch(type.toUpperCase(Locale.ROOT)) {
            case "DIAGNOSIS" -> "진단";
            case "GOAL" -> "관리 목표";
            case "CONCERN" -> "건강 고민";
            case "SYMPTOM" -> "증상";
            default -> "기록";
        };
    }
    private String mealLabel(String type) { return switch(type) { case "BREAKFAST" -> "아침"; case "LUNCH" -> "점심"; case "DINNER" -> "저녁"; default -> type; }; }
    private ChatSession newSession(long userId) { var s=new ChatSession(userId); entities.persist(s); entities.flush(); entities.refresh(s); return s; }
    private ChatSession session(long userId,long id) {
        var s=entities.find(ChatSession.class,id);
        if (s==null || !s.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Chat session not found");
        ensureActive(s);
        return s;
    }
    private void ensureActive(ChatSession s) {
        if (!s.createdAt.plus(java.time.Duration.ofHours(24)).isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.GONE,"대화가 만료되었어요. 새 대화를 시작해 주세요.");
    }
    private void lockUser(long userId) {
        if (entities.find(User.class,userId,LockModeType.PESSIMISTIC_WRITE)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
    }
    private List<ChatModel.Turn> conversation(long sessionId) {
        var latest=entities.createQuery("select m from ChatMessage m where m.sessionId = :id order by m.id desc",ChatMessage.class)
            .setParameter("id",sessionId).setMaxResults(10).getResultList();
        var turns=new ArrayList<ChatModel.Turn>();
        for (int i=latest.size()-1;i>=0;i--) turns.add(new ChatModel.Turn(latest.get(i).role,latest.get(i).content));
        return turns;
    }
}
