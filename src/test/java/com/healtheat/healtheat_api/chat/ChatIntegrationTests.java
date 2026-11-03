package com.healtheat.healtheat_api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healtheat.healtheat_api.ai.HealthAnalysisClient;
import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.mealplan.GeneratedMealPlan;
import com.healtheat.healtheat_api.mealplan.MealGenerationClient;
import com.healtheat.healtheat_api.mealplan.MealPlanDtos;
import com.healtheat.healtheat_api.mealrecord.MealRecordPolicy;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import com.healtheat.healtheat_api.user.preferences.PreferenceDtos;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class ChatIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    @Autowired HealthProfileService health;
    @Autowired PreferenceService preferences;
    @Autowired ChatService chat;
    @MockitoBean ChatModel model;
    @MockitoBean HealthAnalysisClient healthModel;
    @MockitoBean MealGenerationClient mealModel;
    private long user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId(); }
    private Cookie cookie(long id) {
        Instant now=Instant.now();
        return new Cookie("access_token",encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue());
    }
    private String body(String text) throws Exception { return json.writeValueAsString(new ChatDtos.MessageRequest(null,text)); }
    private ChatModel.Classification classify(ChatModel.Intent intent,String food,Integer days,String meal,Long factorId) {
        return new ChatModel.Classification(intent,food,days,meal,null,factorId);
    }
    private long meal(long owner,LocalDate date,String... foods) {
        Long id=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,'LUNCH',false) returning id",Long.class,owner,date);
        for(String food:foods) db.update("insert into meal_record_items(meal_record_id,food_name) values (?,?)",id,food);
        return id;
    }
    private HealthDtos.ProfileResponse seedFactor(long user,String name) {
        return health.confirm(user,new HealthDtos.ConfirmRequest(List.of(new HealthDtos.ConfirmedFactor(null,name,"DIAGNOSIS","REPORTED","원문",true))));
    }
    private void classifyAs(ChatModel.Classification value) { when(model.classify(anyString(),anyList(),anyList())).thenReturn(value); }
    private long sendForAction(long id,String message,ChatModel.Classification classification,String extracted) throws Exception {
        classifyAs(classification);
        if (extracted!=null) when(healthModel.analyze(anyString())).thenReturn(new HealthDtos.HealthFactorAnalysisResponse(List.of(new HealthDtos.AnalysisFactor(extracted,"DIAGNOSIS","REPORTED","원문"))));
        var result=mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(message)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pendingAction.status").value("PENDING")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("pendingAction").path("id").asLong();
    }
    @Test void historyCountIsCalculatedFromOwnRecordsAndLimitedTo30Days() throws Exception {
        long id=user(), other=user(); var today=MealRecordPolicy.today();
        meal(id,today.minusDays(1),"김치찌개","김치찌개"); meal(id,today.minusDays(20),"김치찌개");
        meal(id,today.minusDays(30),"김치찌개"); meal(other,today,"김치찌개");
        classifyAs(classify(ChatModel.Intent.GET_MEAL_HISTORY,"김치찌개",60,null,null));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("최근 60일 김치찌개 몇 번?")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("2회")))
            .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("30일")));
        verify(model,never()).answer(any());
    }
    @Test void adviceUsesRecent14DaysAndSessionsArePrivate() throws Exception {
        long id=user(),other=user();var today=MealRecordPolicy.today();
        meal(id,today.minusDays(13),"두부"); meal(id,today.minusDays(14),"과거"); meal(other,today,"남의 음식");
        classifyAs(classify(ChatModel.Intent.FOOD_CONSULTATION,null,null,null,null));
        when(model.answer(any())).thenReturn("저장된 정보를 바탕으로 참고해 주세요.");
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).contentType(MediaType.APPLICATION_JSON).content(body("오늘 뭐 먹을까?"))).andExpect(status().isForbidden());
        var result=mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("오늘 뭐 먹을까?")))
            .andExpect(status().isOk()).andReturn();
        long sessionId=json.readTree(result.getResponse().getContentAsString()).path("sessionId").asLong();
        verify(model).answer(argThat(c -> c.actualDays()==14 && c.recentMeals().size()==1 && c.recentMeals().get(0).foods().equals(List.of("두부"))));
        mvc.perform(get("/api/chat/sessions/"+sessionId+"/messages").cookie(cookie(id))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/chat/sessions/"+sessionId+"/messages").cookie(cookie(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/sessions").cookie(cookie(other))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void healthAdditionRequiresConfirmationAndPreservesExistingFactors() throws Exception {
        long id=user(),other=user(); String suffix=UUID.randomUUID().toString().substring(0,8);
        String oldName="기존"+suffix,newName="신규"+suffix; seedFactor(id,oldName);
        long action=sendForAction(id,"새로운 진단",classify(ChatModel.Intent.ADD_HEALTH_FACTOR,null,null,null,null),newName);
        assertEquals(1,health.get(id).factors().stream().filter(HealthDtos.ProfileFactor::active).count());
        mvc.perform(get("/api/chat/actions/pending").cookie(cookie(id))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(other)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(id))).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
        var active=health.get(id).factors().stream().filter(HealthDtos.ProfileFactor::active).map(HealthDtos.ProfileFactor::name).toList();
        assertTrue(active.containsAll(List.of(oldName,newName))); assertEquals(2,active.size());
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isConflict());
    }
    @Test void cancellationAndStaleApprovalDoNotChangeHealthInformation() throws Exception {
        long id=user(); String name="새건강"+UUID.randomUUID().toString().substring(0,8);
        long cancelled=sendForAction(id,"추가",classify(ChatModel.Intent.ADD_HEALTH_FACTOR,null,null,null,null),name);
        mvc.perform(post("/api/chat/actions/"+cancelled+"/cancel").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/chat/actions/"+cancelled+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isConflict());
        assertTrue(health.get(id).factors().isEmpty());
        long stale=sendForAction(id,"추가",classify(ChatModel.Intent.ADD_HEALTH_FACTOR,null,null,null,null),name);
        seedFactor(id,"외부변경"+UUID.randomUUID().toString().substring(0,8));
        mvc.perform(post("/api/chat/actions/"+stale+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isConflict());
        assertFalse(health.get(id).factors().stream().anyMatch(f -> f.name().equals(name)));
    }
    @Test void removalRequiresConfirmationAndDoesNotDeleteTheFactor() throws Exception {
        long id=user(); String name="관리"+UUID.randomUUID().toString().substring(0,8);
        var factor=seedFactor(id,name).factors().get(0);
        long action=sendForAction(id,"더 이상 관리하지 않아",classify(ChatModel.Intent.REMOVE_HEALTH_FACTOR,null,null,null,factor.factorId()),null);
        assertTrue(health.get(id).factors().get(0).active());
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        var result=health.get(id).factors(); assertEquals(1,result.size()); assertFalse(result.get(0).active());
    }
    @Test void chatRegeneratesOnlyRequestedMealThroughExistingService() throws Exception {
        long id=user(); var today=MealRecordPolicy.today();
        preferences.update(id,new PreferenceDtos.UpdateRequest(false,List.of(),List.of(),List.of(),PreferenceDtos.AllergyStatus.NONE,List.of()));
        Long plan=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,id,today);
        for(String type:List.of("BREAKFAST","LUNCH","DINNER")) {
            Long recommendation=db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current,version) values (?,?,'HOME_COOKED',true,1) returning id",Long.class,plan,type);
            db.update("insert into meal_items(meal_recommendation_id,food_name,food_role,preparation_type) values (?,'밥','STAPLE','READY_TO_EAT')",recommendation);
        }
        classifyAs(new ChatModel.Classification(ChatModel.Intent.REGENERATE_MEAL,null,null,"DINNER","가벼운 음식",null));
        when(mealModel.regenerate(any())).thenReturn(new GeneratedMealPlan.Meal("DINNER","HOME_COOKED","새 저녁",List.of(new GeneratedMealPlan.Item("샐러드","MAIN","READY_TO_EAT",null,null))));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("저녁 가볍게 다시 추천해줘")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("샐러드")))
            .andExpect(jsonPath("$.updatedMealPlan.meals.length()").value(3));
        assertEquals(3L,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=? and is_current=true",Long.class,plan));
        assertEquals(1L,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=? and meal_type='DINNER' and is_current=false",Long.class,plan));
        verify(mealModel).regenerate(argThat(c -> c.mealType().equals("DINNER") && c.additionalRequest().equals("가벼운 음식")));
    }
    @Test void noHistoryStillAllowsAdvice() throws Exception {
        long id=user(); classifyAs(classify(ChatModel.Intent.FOOD_CONSULTATION,null,null,null,null));
        when(model.answer(any())).thenReturn("기록은 없지만 일반적인 정보를 안내할게요.");
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("오늘 뭐 먹을까?")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("일반적인")));
        verify(model).answer(argThat(c -> c.recentMeals().isEmpty()));
    }
    @Test void explicitThirtyDayAdviceUsesRequestedWindowAndPreservesConversation() throws Exception {
        long id=user(); var today=MealRecordPolicy.today();
        meal(id,today.minusDays(20),"밥"); meal(id,today.minusDays(30),"보관 범위 밖");
        classifyAs(classify(ChatModel.Intent.FOOD_CONSULTATION,null,30,null,null)); when(model.answer(any())).thenReturn("식사 기록을 참고했어요.");
        var first=mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("최근 30일 어떤가요?")))
            .andExpect(status().isOk()).andReturn();
        long session=json.readTree(first.getResponse().getContentAsString()).path("sessionId").asLong();
        verify(model).answer(argThat(c -> c.actualDays()==30 && c.recentMeals().size()==1 && c.recentMeals().get(0).foods().equals(List.of("밥"))));
        String next=json.writeValueAsString(new ChatDtos.MessageRequest(session,"그럼 오늘은요?"));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(next)).andExpect(status().isOk());
        verify(model,atLeastOnce()).classify(eq("그럼 오늘은요?"),anyList(),argThat(turns -> turns.size()==2 && turns.get(0).role().equals("USER") && turns.get(1).role().equals("ASSISTANT")));
        mvc.perform(get("/api/chat/sessions/"+session+"/messages").cookie(cookie(id))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
    }
    @Test void updatingExistingFactorRequiresConfirmation() throws Exception {
        long id=user(); String name="관리"+UUID.randomUUID().toString().substring(0,8);
        var factor=seedFactor(id,name).factors().get(0);
        classifyAs(classify(ChatModel.Intent.UPDATE_HEALTH_FACTOR,null,null,null,factor.factorId()));
        when(healthModel.analyze(anyString())).thenReturn(new HealthDtos.HealthFactorAnalysisResponse(List.of(new HealthDtos.AnalysisFactor(name,"DIAGNOSIS","IMPROVED","상태가 개선됨"))));
        var response=mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("상태가 개선됐어")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pendingAction.status").value("PENDING")).andReturn();
        long action=json.readTree(response.getResponse().getContentAsString()).path("pendingAction").path("id").asLong();
        assertEquals("REPORTED",health.get(id).factors().get(0).reportedStatus());
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isOk());
        assertEquals("IMPROVED",health.get(id).factors().get(0).reportedStatus());
    }
    @Test void concurrentConfirmationAppliesChangeOnlyOnce() throws Exception {
        long id=user(); String name="건강"+UUID.randomUUID().toString().substring(0,8);
        long action=sendForAction(id,"건강 정보 추가",classify(ChatModel.Intent.ADD_HEALTH_FACTOR,null,null,null,null),name);
        var start=new java.util.concurrent.CountDownLatch(1); var ready=new java.util.concurrent.CountDownLatch(2);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> task=() -> { ready.countDown(); if (!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                try { chat.confirm(id,action); return "confirmed"; } catch (org.springframework.web.server.ResponseStatusException ex) { return Integer.toString(ex.getStatusCode().value()); } };
            var first=executor.submit(task); var second=executor.submit(task); assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            assertEquals(java.util.Set.of("confirmed","409"),java.util.Set.of(first.get(30,java.util.concurrent.TimeUnit.SECONDS),second.get(30,java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals(1,health.get(id).factors().stream().filter(f -> f.name().equals(name) && f.active()).count());
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    @Test void incompleteLegacyHealthDataDoesNotGetOverwrittenOnConfirmation() throws Exception {
        long id=user(); String name="기존"+UUID.randomUUID().toString().substring(0,8);
        Long master=db.queryForObject("insert into health_factors(code,name,type,supported) values (?,?,?,?) returning id",Long.class,"TEST_"+UUID.randomUUID(),name,"DIAGNOSIS","UNSUPPORTED");
        db.update("insert into user_health_factors(user_id,health_factor_id,reported_status,is_active) values (?,?,null,true)",id,master);
        String proposed="새정보"+UUID.randomUUID().toString().substring(0,8);
        long action=sendForAction(id,"새 정보 추가",classify(ChatModel.Intent.ADD_HEALTH_FACTOR,null,null,null,null),proposed);
        mvc.perform(post("/api/chat/actions/"+action+"/confirm").cookie(cookie(id)).with(csrf())).andExpect(status().isConflict());
        assertEquals(1,health.get(id).factors().size());
        assertTrue(health.get(id).factors().get(0).active());
    }
    @Test void regenerationWithoutTodayPlanExplainsNextStep() throws Exception {
        long id=user(); classifyAs(new ChatModel.Classification(ChatModel.Intent.REGENERATE_MEAL,null,null,"DINNER",null,null));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("저녁 다시 추천")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("먼저 오늘 식단")));
        verifyNoInteractions(mealModel);
        mvc.perform(get("/api/chat/sessions").cookie(cookie(id))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
}
