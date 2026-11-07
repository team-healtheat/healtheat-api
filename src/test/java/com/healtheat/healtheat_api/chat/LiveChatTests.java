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
    @Test void familyIsNotStored() {
        long id=user(); var r=send(id,"우리 엄마가 당뇨병 진단을 받았어.");
        assertNull(r.updatedHealthProfile()); assertTrue(health.get(id).factors().isEmpty());
    }
    @Test void unrelatedRequestIsRejected() {
        long id=user(); var r=send(id,"제주도 여행 코스를 추천해 줘.");
        assertEquals("OUT_OF_SCOPE",r.intent()); assertNull(r.updatedHealthProfile()); assertNull(r.updatedMealPlan());
        assertTrue(r.reply().contains("식사 기록"));
    }

}
