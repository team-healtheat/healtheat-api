package com.healtheat.healtheat_api.chat;

import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.mealrecord.MealRecordPolicy;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="RUN_LIVE_CHAT_TEST", matches="true")
class LiveChatTests {
    @Autowired ChatService chat;
    @Autowired UserRepository users;
    @Autowired HealthProfileService health;
    @Autowired JdbcTemplate db;
    @Autowired com.healtheat.healtheat_api.user.preferences.PreferenceService preferences;
    private long user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId(); }
    private ChatDtos.Response send(long id,String text) { return chat.send(id,new ChatDtos.MessageRequest(null,text)); }
    private void record(long id,int days) {
        Long meal=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation) values (?,?,'LUNCH',false) returning id",Long.class,id,MealRecordPolicy.today().minusDays(days));
        db.update("insert into meal_record_items(meal_record_id,food_name) values (?,'김치찌개')",meal);
    }
    @Test void foodAdvice() {
        long id=user(); var r=send(id,"바나나 먹어도 될까?");
        assertEquals("FOOD_CONSULTATION",r.intent()); assertFalse(r.reply().isBlank());
    }
    @Test void recordCountAndFollowUp() {
        long id=user(); record(id,1); record(id,3);
        var r=send(id,"최근 2주 동안 김치찌개 몇 번 먹었어?");
        assertEquals("GET_MEAL_HISTORY",r.intent()); assertTrue(r.reply().contains("2회"));
        var next=chat.send(id,new ChatDtos.MessageRequest(r.sessionId(),"그럼 너무 자주 먹은 거야?"));
        assertEquals("FOOD_CONSULTATION",next.intent()); assertFalse(next.reply().isBlank());
    }
    @Test void addPersonalDiagnosis() {
        long id=user(); var r=send(id,"나 당뇨병 진단받았어.");
        assertEquals("ADD_HEALTH_FACTOR",r.intent()); assertNotNull(r.updatedHealthProfile());
        assertTrue(health.get(id).factors().stream().anyMatch(f -> f.active() && f.name().contains("당뇨")));
    }
    @Test void familyIsNotStored() {
        long id=user(); var r=send(id,"우리 엄마가 당뇨병 진단을 받았어.");
        assertNull(r.updatedHealthProfile()); assertTrue(health.get(id).factors().isEmpty());
    }
    @Test void unrelatedRequestIsRejected() {
        long id=user(); var r=send(id,"제주도 여행 코스를 추천해 줘.");
        assertEquals("OUT_OF_SCOPE",r.intent()); assertNull(r.updatedHealthProfile()); assertNull(r.updatedMealPlan());
        assertTrue(r.reply().contains("식사 기록"));
    }
    @Test void healthConcernIsNotADiagnosis() {
        long id=user(); var r=send(id,"요즘 콜레스테롤이 높을까 봐 걱정돼.");
        assertEquals("ADD_HEALTH_FACTOR",r.intent()); assertNotNull(r.updatedHealthProfile());
        assertTrue(health.get(id).factors().stream().anyMatch(f -> f.active() && f.name().contains("콜레스테롤") && !"DIAGNOSIS".equalsIgnoreCase(f.type())));
    }
    @Test void actualDinnerRegenerationThroughChat() {
        long id=user();
        preferences.update(id,
            new com.healtheat.healtheat_api.user.preferences.PreferenceDtos.UpdateRequest(false,java.util.List.of(),java.util.List.of(),java.util.List.of(),
                com.healtheat.healtheat_api.user.preferences.PreferenceDtos.AllergyStatus.NONE,java.util.List.of()));
        Long plan=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,id,MealRecordPolicy.today());
        for (String type:java.util.List.of("BREAKFAST","LUNCH","DINNER")) {
            Long recommendation=db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,source_type,is_current,version) values (?,?,'HOME_COOKED',true,1) returning id",Long.class,plan,type);
            db.update("insert into meal_items(meal_recommendation_id,food_name,food_role,preparation_type) values (?,'밥','STAPLE','READY_TO_EAT')",recommendation);
        }
        var r=send(id,"오늘 저녁은 고기가 당겨. 저녁 식단 다시 추천해줘.");
        assertEquals("REGENERATE_MEAL",r.intent()); assertNotNull(r.updatedMealPlan());
        assertEquals(1L,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=? and meal_type='DINNER' and is_current=false",Long.class,plan));
        assertEquals(2L,db.queryForObject("select count(*) from meal_recommendations where meal_plan_id=? and meal_type in ('BREAKFAST','LUNCH') and version=1 and is_current=true",Long.class,plan));
    }
    @Test void regenerationAsksForMealWhenMissing() {
        long id=user(); var r=send(id,"오늘은 고기가 당겨. 식단 다시 추천해줘.");
        assertEquals("REGENERATE_MEAL",r.intent()); assertTrue(r.reply().contains("아침, 점심, 저녁"));
    }
}
