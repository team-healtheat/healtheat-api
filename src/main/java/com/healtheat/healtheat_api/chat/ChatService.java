package com.healtheat.healtheat_api.chat;

import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.mealplan.ChatMealFacade;
import com.healtheat.healtheat_api.mealrecord.MealHistoryQueryService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
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
    ChatService(EntityManager entities,ChatModel model,HealthProfileService health,PreferenceService preferences,
                MealHistoryQueryService history,ChatMealFacade meals) {
        this.entities=entities; this.model=model; this.health=health; this.preferences=preferences; this.history=history; this.meals=meals;
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
            case OUT_OF_SCOPE -> reply="저는 음식과 식습관 상담, 건강 정보 관리, 식사 기록 조회, 식단 재추천을 도와드릴 수 있어요. 이 중에서 궁금한 점을 말씀해 주세요.";
            case GENERAL_CHAT -> reply="안녕하세요! 음식과 식습관 상담, 건강 정보 관리, 식사 기록 조회, 식단 재추천을 도와드릴게요.";
            case ADD_HEALTH_FACTOR, UPDATE_HEALTH_FACTOR, REMOVE_HEALTH_FACTOR -> {
                // Re-read the profile after acquiring the same user lock used by profile updates.
                long sessionId=session.id;
                entities.flush();
                entities.clear();
                lockUser(userId);
                session=session(userId,sessionId);
                var current=health.get(userId);
                var proposedEdits=model.healthEdits(request.message(),current.factors().stream().map(f -> new ChatModel.Factor(f.factorId(),f.name(),f.type(),f.reportedStatus(),f.active())).toList());
                if(proposedEdits!=null && !proposedEdits.isEmpty()) {
                    updatedHealthProfile=applyEdits(userId,current,proposedEdits);
                    reply="건강 정보에 반영했어요: "+String.join(", ",proposedEdits.stream().map(e -> {
                        String name=e.factorId()==null ? e.name() : current.factors().stream().filter(f -> e.factorId().equals(f.factorId())).map(HealthDtos.ProfileFactor::name).findFirst().orElse("건강 정보");
                        return name+("REMOVE".equals(e.action()) ? " 제외" : " 반영");
                    }).toList());
                    break;
                }
                if(proposedEdits!=null) { reply="변경할 건강 정보를 확인하지 못했어요. 어떤 항목을 어떻게 바꿀지 다시 알려 주세요."; break; }
                var changes=propose(userId,request.message(),current,classification);
                if (changes.isEmpty()) reply="변경할 건강 정보를 확인하지 못했어요. 어떤 항목을 어떻게 바꿀지 다시 알려 주세요.";
                else {
                    updatedHealthProfile=applyHealthChanges(userId,current,changes,classification.intent().name());
                    String names=String.join(", ",changes.stream().map(c -> c.name()+" ("+healthTypeLabel(c.type())+")").toList());
                    String verb=switch (classification.intent()) {
                        case ADD_HEALTH_FACTOR -> "건강 정보에 반영했어요: ";
                        case UPDATE_HEALTH_FACTOR -> "건강 정보를 수정했어요: ";
                        case REMOVE_HEALTH_FACTOR -> "건강 정보에서 제외했어요: ";
                        default -> throw new IllegalStateException();
                    };
                    reply=verb+names;
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
        session=session(userId,session.id);
        entities.persist(new ChatMessage(session.id,"USER",request.message().trim()));
        entities.persist(new ChatMessage(session.id,"ASSISTANT",reply));
        session.updatedAt=Instant.now();
        return new ChatDtos.Response(session.id,reply,classification.intent().name(),updatedHealthProfile,updatedMealPlan);
    }
    private HealthDtos.ProfileResponse applyEdits(long userId,HealthDtos.ProfileResponse current,List<ChatModel.HealthEdit> edits) {
        if(edits.size()>50) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Too many health changes");
        var merged=new ArrayList<HealthDtos.ConfirmedFactor>();
        for(var f:current.factors()) merged.add(new HealthDtos.ConfirmedFactor(f.factorId(),f.name(),f.type(),f.reportedStatus(),f.originalText(),f.active()));
        var touched=new java.util.HashSet<Long>();
        for(var edit:edits) {
            if(edit==null || edit.action()==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid health change");
            if("ADD".equals(edit.action())) {
                if(edit.factorId()!=null || edit.name()==null || edit.name().isBlank() || edit.type()==null || edit.type().isBlank() || edit.reportedStatus()==null || edit.reportedStatus().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid health change");
                if(edit.name().length()>200 || edit.type().length()>80 || edit.reportedStatus().length()>80 || (edit.originalText()!=null && edit.originalText().length()>1000)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid health change");
                applyChange(merged,new ChatDtos.Change(null,edit.name(),edit.type(),edit.reportedStatus(),edit.originalText(),true),"ADD_HEALTH_FACTOR");
            } else if("REMOVE".equals(edit.action()) || "UPDATE".equals(edit.action())) {
                if(edit.factorId()==null || !touched.add(edit.factorId())) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid health change");
                var existing=current.factors().stream().filter(f -> f.active() && edit.factorId().equals(f.factorId())).toList();
                if(existing.size()!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"건강 정보 대상을 다시 확인해 주세요.");
                var f=existing.get(0);
                String status="UPDATE".equals(edit.action()) ? edit.reportedStatus() : f.reportedStatus();
                if(status==null || status.isBlank() || status.length()>80) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid health change");
                applyChange(merged,new ChatDtos.Change(f.factorId(),f.name(),f.type(),status,f.originalText(),!"REMOVE".equals(edit.action())),"UPDATE_HEALTH_FACTOR");
            } else throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid health change");
        }
        entities.flush(); entities.clear();
        return health.confirm(userId,new HealthDtos.ConfirmRequest(merged));
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
    private HealthDtos.ProfileResponse applyHealthChanges(long userId,HealthDtos.ProfileResponse current,List<ChatDtos.Change> changes,String actionType) {
        var merged=new ArrayList<HealthDtos.ConfirmedFactor>();
        for (var f: current.factors()) {
            if (f.factorId()==null || f.name()==null || f.type()==null || f.reportedStatus()==null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"기존 건강 정보를 확인한 뒤 다시 시도해 주세요.");
            merged.add(new HealthDtos.ConfirmedFactor(f.factorId(),f.name(),f.type(),f.reportedStatus(),f.originalText(),f.active()));
        }
        for (var change: changes) applyChange(merged,change,actionType);
        if (merged.size()>50) throw new ResponseStatusException(HttpStatus.CONFLICT,"건강 정보 항목 수가 한도를 초과합니다.");
        entities.flush();
        entities.clear();
        return health.confirm(userId,new HealthDtos.ConfirmRequest(merged));
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
