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
import org.junit.jupiter.api.BeforeEach;
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
    @BeforeEach void legacyHealthMock() { when(model.healthEdits(anyString(),anyList())).thenReturn(null); }
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
    @Test void sessionStartResumeAndEndKeepHealthData() throws Exception {
        long id=user(),other=user(); String name="건강"+UUID.randomUUID().toString().substring(0,8); seedFactor(id,name);
        var first=mvc.perform(post("/api/chat/sessions").cookie(cookie(id)).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").exists()).andReturn();
        var data=json.readTree(first.getResponse().getContentAsString()); long session=data.path("id").asLong();
        assertEquals(java.time.Duration.ofHours(24),java.time.Duration.between(Instant.parse(data.path("createdAt").asText()),Instant.parse(data.path("expiresAt").asText())));
        mvc.perform(post("/api/chat/sessions").cookie(cookie(id)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(session));
        classifyAs(classify(ChatModel.Intent.GENERAL_CHAT,null,null,null,null));
        String content=json.writeValueAsString(new ChatDtos.MessageRequest(session,"안녕"));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isOk());
        mvc.perform(delete("/api/chat/sessions/"+session).cookie(cookie(other)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/chat/sessions/"+session).cookie(cookie(id))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/chat/sessions/"+session).cookie(cookie(id)).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0L,db.queryForObject("select count(*) from chat_messages where chat_session_id=?",Long.class,session));
        assertEquals(1,health.get(id).factors().size());
        var next=mvc.perform(post("/api/chat/sessions").cookie(cookie(id)).with(csrf())).andExpect(status().isOk()).andReturn();
        assertNotEquals(session,json.readTree(next.getResponse().getContentAsString()).path("id").asLong());
    }
    @Test void expiredSessionsAreHiddenRejectedAndDeleted() throws Exception {
        long id=user(); var result=mvc.perform(post("/api/chat/sessions").cookie(cookie(id)).with(csrf())).andExpect(status().isOk()).andReturn();
        long session=json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        db.update("insert into chat_messages(chat_session_id,role,content) values (?,'USER','가상 테스트')",session);
        db.update("update chat_sessions set created_at=now()-interval '25 hours' where id=?",session);
        mvc.perform(get("/api/chat/sessions").cookie(cookie(id))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/chat/sessions/"+session+"/messages").cookie(cookie(id))).andExpect(status().isGone());
        String content=json.writeValueAsString(new ChatDtos.MessageRequest(session,"안녕"));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isGone());
        chat.deleteExpired();
        assertEquals(0L,db.queryForObject("select count(*) from chat_sessions where id=?",Long.class,session));
        assertEquals(0L,db.queryForObject("select count(*) from chat_messages where chat_session_id=?",Long.class,session));
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
    @Test void healthChangesAreDirectedToProfileAndNeverSaved() throws Exception {
        long id=user(); var before=seedFactor(id,"당뇨병");
        for (var intent:List.of(ChatModel.Intent.ADD_HEALTH_FACTOR,ChatModel.Intent.UPDATE_HEALTH_FACTOR,ChatModel.Intent.REMOVE_HEALTH_FACTOR)) {
            classifyAs(classify(intent,null,null,null,null));
            mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("건강정보를 변경해줘")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("마이페이지")))
                .andExpect(jsonPath("$.updatedHealthProfile").doesNotExist());
        }
        assertEquals(before,health.get(id));
        verify(model,never()).healthEdits(anyString(),anyList());
        verifyNoInteractions(healthModel);
    }
    @Test void mealRegenerationIsDirectedToMealScreenWithoutChangingPlan() throws Exception {
        long id=user(); var today=MealRecordPolicy.today();
        Long plan=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,id,today);
        db.update("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current,version) values (?,'DINNER','HOME_COOKED',true,1)",plan);
        classifyAs(new ChatModel.Classification(ChatModel.Intent.REGENERATE_MEAL,null,null,"DINNER",null,null));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("저녁 다시 추천")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("식단 화면")))
            .andExpect(jsonPath("$.updatedMealPlan").doesNotExist());
        assertEquals(1L,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=? and is_current=true",Long.class,plan));
        verifyNoInteractions(mealModel);
    }
    @Test void partialFoodQueryIdentifiesTheStoredDishWithoutClaimingUnrecordedMeals() throws Exception {
        long id=user(), other=user(); var today=MealRecordPolicy.today();
        meal(id,today,"돼지고기 안심채소덮밥"); meal(other,today,"돼지 안심 스테이크");
        classifyAs(classify(ChatModel.Intent.GET_MEAL_HISTORY,"돼지 안심",14,null,null));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("돼지 안심 몇 번 먹었어?")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("돼지고기 안심채소덮밥")))
            .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("1회")));
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
    @Test void familyInformationDoesNotModifyOwnHealthProfile() throws Exception {
        long id=user(); classifyAs(classify(ChatModel.Intent.GENERAL_CHAT,null,null,null,null));
        when(model.answer(any())).thenReturn("가족분의 건강이 걱정되겠어요.");
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("엄마가 당뇨병이야")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updatedHealthProfile").doesNotExist());
        assertTrue(health.get(id).factors().isEmpty()); verifyNoInteractions(healthModel);
    }
    @Test void unsupportedRequestsReceiveFeatureGuidanceWithoutChangingData() throws Exception {
        long id=user(); classifyAs(classify(ChatModel.Intent.OUT_OF_SCOPE,null,null,null,null));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("여행지 추천해줘")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("식사 기록 조회")))
            .andExpect(jsonPath("$.updatedHealthProfile").doesNotExist()).andExpect(jsonPath("$.updatedMealPlan").doesNotExist());
        verify(model,never()).answer(any()); verifyNoInteractions(healthModel,mealModel);
        assertTrue(health.get(id).factors().isEmpty());
    }
    @Test void greetingsReceiveBriefGuidanceWithoutStartingConsultation() throws Exception {
        long id=user(); classifyAs(classify(ChatModel.Intent.GENERAL_CHAT,null,null,null,null));
        mvc.perform(post("/api/chat/messages").cookie(cookie(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("안녕")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("안녕하세요")));
        verify(model,never()).answer(any()); verifyNoInteractions(healthModel,mealModel);
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

}
