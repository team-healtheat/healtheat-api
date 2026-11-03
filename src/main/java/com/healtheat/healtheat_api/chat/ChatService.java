package com.healtheat.healtheat_api.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.mealplan.ChatMealFacade;
import com.healtheat.healtheat_api.mealrecord.MealHistoryQueryService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final ChatMealFacade meals;
    private final ObjectMapper json;
    ChatService(EntityManager entities,ChatModel model,HealthProfileService health,PreferenceService preferences,
                MealHistoryQueryService history,ChatMealFacade meals,ObjectMapper json) {
        this.entities=entities; this.model=model; this.health=health; this.preferences=preferences; this.history=history; this.meals=meals; this.json=json;
    }
    @Transactional
    public ChatDtos.Response send(long userId,ChatDtos.MessageRequest request) {
        if (entities.find(User.class,userId)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        ChatSession session=request.sessionId()==null ? newSession(userId) : session(userId,request.sessionId());
        if (request.sessionId()!=null) entities.lock(session,LockModeType.PESSIMISTIC_WRITE);
        var profile=health.get(userId);
        var factors=profile.factors().stream().map(f -> new ChatModel.Factor(f.factorId(),f.name(),f.type(),f.reportedStatus(),f.active())).toList();
        var recentTurns=conversation(session.id);
        var classification=model.classify(request.message(),factors,recentTurns);
        if (classification==null || classification.intent()==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Chat intent response was invalid");
        validateClassification(classification);
        ChatDtos.PendingAction pending=null;
        com.healtheat.healtheat_api.mealplan.MealPlanDtos.Plan updatedMealPlan=null;
        String reply;
        switch (classification.intent()) {
            case GET_MEAL_HISTORY -> reply=historyReply(userId,classification);
            case GET_HEALTH_PROFILE -> reply=profileReply(profile);
            case ADD_HEALTH_FACTOR, UPDATE_HEALTH_FACTOR, REMOVE_HEALTH_FACTOR -> {
                var changes=propose(userId,request.message(),profile,classification);
                if (changes.isEmpty()) reply="변경할 건강 정보를 확인하지 못했어요. 어떤 항목을 어떻게 바꿀지 다시 알려 주세요.";
                else {
                    PendingChatAction action=new PendingChatAction(); action.userId=userId; action.actionType=classification.intent().name();
                    action.payload=new ChatDtos.ActionPayload(session.id,fingerprint(profile),changes); action.status="PENDING"; entities.persist(action);
                    pending=actionResponse(action);
                    reply="건강 정보 변경 내용을 확인해 주세요. 확인하기 전에는 저장하지 않아요.";
                }
            }
            case REGENERATE_MEAL -> {
                if (classification.mealType()==null || !Set.of("BREAKFAST","LUNCH","DINNER").contains(classification.mealType()))
                    reply="아침, 점심, 저녁 중 어떤 식사를 다시 추천할까요?";
                else {
                    // The classifier read the profile before the meal service takes the user lock.
                    // Discard those managed entities so regeneration reads the current profile after locking.
                    long sessionId=session.id;
                    entities.flush();
                    entities.clear();
                    session=session(userId,sessionId);
                    updatedMealPlan=meals.regenerateToday(userId,classification.mealType(),classification.additionalRequest());
                    if (updatedMealPlan==null) {
                        reply="오늘의 식단이나 선택한 끼니가 없어요. 먼저 오늘 식단을 생성해 주세요.";
                    } else {
                        var updated=updatedMealPlan.meals().stream().filter(m -> m.mealType().equals(classification.mealType())).findFirst().orElseThrow();
                        String label=mealLabel(updated.mealType());
                        reply=label+" 식단을 다시 추천했어요: "+String.join(", ",updated.items().stream().map(i -> i.foodName()).toList());
                    }
                }
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
        entities.persist(new ChatMessage(session.id,"USER",request.message().trim()));
        entities.persist(new ChatMessage(session.id,"ASSISTANT",reply));
        session.updatedAt=Instant.now();
        return new ChatDtos.Response(session.id,reply,classification.intent().name(),pending,updatedMealPlan);
    }
    @Transactional(readOnly=true)
    public List<ChatDtos.Session> sessions(long userId) {
        return entities.createQuery("select s from ChatSession s where s.userId = :userId order by s.updatedAt desc, s.id desc",ChatSession.class)
            .setParameter("userId",userId).getResultList().stream().map(s -> new ChatDtos.Session(s.id,s.createdAt,s.updatedAt)).toList();
    }
    @Transactional(readOnly=true)
    public List<ChatDtos.Message> messages(long userId,long sessionId) {
        session(userId,sessionId);
        return entities.createQuery("select m from ChatMessage m where m.sessionId = :id order by m.id",ChatMessage.class)
            .setParameter("id",sessionId).getResultList().stream().map(m -> new ChatDtos.Message(m.id,m.role,m.content,m.createdAt)).toList();
    }
    @Transactional(readOnly=true)
    public List<ChatDtos.PendingAction> pending(long userId) {
        return entities.createQuery("select a from PendingChatAction a where a.userId = :userId and a.status = 'PENDING' and a.actionType in ('ADD_HEALTH_FACTOR','UPDATE_HEALTH_FACTOR','REMOVE_HEALTH_FACTOR') order by a.id",PendingChatAction.class)
            .setParameter("userId",userId).getResultList().stream().map(this::actionResponse).toList();
    }
    @Transactional
    public ChatDtos.ActionResult confirm(long userId,long actionId) {
        lockUser(userId);
        var action=action(userId,actionId);
        if (!"PENDING".equals(action.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 처리된 요청입니다.");
        var current=health.get(userId);
        if (!fingerprint(current).equals(action.payload.baselineHash())) throw new ResponseStatusException(HttpStatus.CONFLICT,"건강 정보가 변경되어 다시 확인이 필요합니다.");
        var merged=new ArrayList<HealthDtos.ConfirmedFactor>();
        for (var f: current.factors()) {
            if (f.factorId()==null || f.name()==null || f.type()==null || f.reportedStatus()==null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"기존 건강 정보를 확인한 뒤 다시 시도해 주세요.");
            merged.add(new HealthDtos.ConfirmedFactor(f.factorId(),f.name(),f.type(),f.reportedStatus(),f.originalText(),f.active()));
        }
        for (var change: action.payload.changes()) applyChange(merged,change,action.actionType);
        if (merged.size()>50) throw new ResponseStatusException(HttpStatus.CONFLICT,"건강 정보 항목 수가 한도를 초과합니다.");
        // A bulk update in health.confirm bypasses JPA's first-level cache. Clear loaded profile entities first.
        entities.clear();
        action=action(userId,actionId);
        var updated=health.confirm(userId,new HealthDtos.ConfirmRequest(merged));
        action.status="CONFIRMED"; action.confirmedAt=Instant.now();
        return new ChatDtos.ActionResult(action.id,action.status,updated);
    }
    @Transactional
    public ChatDtos.ActionResult cancel(long userId,long actionId) {
        lockUser(userId);
        var action=action(userId,actionId);
        if (!"PENDING".equals(action.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 처리된 요청입니다.");
        action.status="CANCELLED";
        return new ChatDtos.ActionResult(action.id,action.status,null);
    }
    private List<ChatDtos.Change> propose(long userId,String text,HealthDtos.ProfileResponse profile,ChatModel.Classification classification) {
        if (classification.intent()==ChatModel.Intent.REMOVE_HEALTH_FACTOR || classification.intent()==ChatModel.Intent.UPDATE_HEALTH_FACTOR) {
            if (classification.targetFactorId()==null) return List.of();
            var matches=profile.factors().stream().filter(f -> f.factorId().equals(classification.targetFactorId()) && f.active()).toList();
            if (matches.size()!=1) return List.of();
            var target=matches.get(0);
            if (classification.intent()==ChatModel.Intent.REMOVE_HEALTH_FACTOR)
                return List.of(new ChatDtos.Change(target.factorId(),target.name(),target.type(),target.reportedStatus(),target.originalText(),false));
            var candidates=health.analyze(userId,text).factors();
            if (candidates.size()!=1) return List.of();
            var candidate=candidates.get(0);
            if (!same(candidate.name(),target.name()) || !same(candidate.type(),target.type())) return List.of();
            return List.of(new ChatDtos.Change(target.factorId(),target.name(),target.type(),candidate.reportedStatus(),candidate.originalText(),true));
        }
        var candidates=health.analyze(userId,text).factors();
        if (candidates.isEmpty() || candidates.size()>50) return List.of();
        var keys=new java.util.HashSet<String>();
        var changes=new ArrayList<ChatDtos.Change>();
        for (var c: candidates) {
            String key=c.name().trim().toLowerCase(Locale.ROOT)+"\u0000"+c.type().trim().toLowerCase(Locale.ROOT);
            if (!keys.add(key)) return List.of();
            changes.add(new ChatDtos.Change(c.factorId(),c.name().trim(),c.type().trim(),c.reportedStatus().trim(),c.originalText(),true));
        }
        return changes;
    }
    private void applyChange(List<HealthDtos.ConfirmedFactor> values,ChatDtos.Change change,String actionType) {
        int index=-1;
        for (int i=0;i<values.size();i++) {
            var f=values.get(i);
            if ((change.factorId()!=null && change.factorId().equals(f.factorId())) || (same(change.name(),f.name()) && same(change.type(),f.type()))) {
                if (index!=-1) throw new ResponseStatusException(HttpStatus.CONFLICT,"건강 정보가 중복되어 다시 확인이 필요합니다.");
                index=i;
            }
        }
        var updated=new HealthDtos.ConfirmedFactor(change.factorId(),change.name(),change.type(),change.reportedStatus(),change.originalText(),change.active());
        if (index<0) {
            if (!"ADD_HEALTH_FACTOR".equals(actionType)) throw new ResponseStatusException(HttpStatus.CONFLICT,"변경 대상이 없습니다.");
            values.add(updated);
        } else {
            var original=values.get(index);
            if (!same(change.name(),original.name()) || !same(change.type(),original.type())) throw new ResponseStatusException(HttpStatus.CONFLICT,"건강 정보가 일치하지 않습니다.");
            values.set(index,new HealthDtos.ConfirmedFactor(original.factorId(),original.name(),original.type(),change.reportedStatus(),change.originalText(),change.active()));
        }
    }
    private boolean same(String a,String b) { return a!=null && b!=null && a.trim().equalsIgnoreCase(b.trim()); }
    private String fingerprint(HealthDtos.ProfileResponse profile) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(profile.factors()))); }
        catch (JsonProcessingException | NoSuchAlgorithmException ex) { throw new IllegalStateException("Could not verify health profile",ex); }
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
    private ChatSession newSession(long userId) { var s=new ChatSession(userId); entities.persist(s); return s; }
    private ChatSession session(long userId,long id) {
        var s=entities.find(ChatSession.class,id);
        if (s==null || !s.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Chat session not found");
        return s;
    }
    private PendingChatAction action(long userId,long id) {
        var a=entities.find(PendingChatAction.class,id);
        if (a==null || !a.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Action not found");
        return a;
    }
    private void lockUser(long userId) {
        if (entities.find(User.class,userId,LockModeType.PESSIMISTIC_WRITE)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
    }
    private ChatDtos.PendingAction actionResponse(PendingChatAction a) { return new ChatDtos.PendingAction(a.id,a.payload.sessionId(),a.actionType,a.status,a.payload.changes()); }
    private List<ChatModel.Turn> conversation(long sessionId) {
        var latest=entities.createQuery("select m from ChatMessage m where m.sessionId = :id order by m.id desc",ChatMessage.class)
            .setParameter("id",sessionId).setMaxResults(10).getResultList();
        var turns=new ArrayList<ChatModel.Turn>();
        for (int i=latest.size()-1;i>=0;i--) turns.add(new ChatModel.Turn(latest.get(i).role,latest.get(i).content));
        return turns;
    }
}
