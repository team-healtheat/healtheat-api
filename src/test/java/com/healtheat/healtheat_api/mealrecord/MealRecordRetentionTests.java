package com.healtheat.healtheat_api.mealrecord;

import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class MealRecordRetentionTests {
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired MealRecordService records;
    @Autowired MealHistoryQueryService history;
    @Autowired MealRecordRetentionService retention;
    @Autowired MealRecordRetentionScheduler scheduler;
    private long user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","hash")).getId(); }
    private long stored(long userId, LocalDate date, String name, Long recommendationId) {
        Long id=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation,recommendation_id) values (?,?,'LUNCH',?,?) returning id",Long.class,userId,date,recommendationId!=null,recommendationId);
        db.update("insert into meal_record_items(meal_record_id,food_name) values (?,?)",id,name);
        return id;
    }
    @Test void deletesOnlyRecordsOlderThan30CalendarDaysAndKeepsRecommendations() {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        Long plan=db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id",Long.class,user,today.minusDays(30));
        Long recommendation=db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,is_current) values (?,'LUNCH',true) returning id",Long.class,plan);
        Long mealItem=db.queryForObject("insert into meal_items(meal_recommendation_id,food_name) values (?,'요리') returning id",Long.class,recommendation);
        Long recipe=db.queryForObject("insert into recipes(meal_item_id,title) values (?,'레시피') returning id",Long.class,mealItem);
        long expired=stored(user,today.minusDays(30),"오래된 식사",recommendation);
        long retained=stored(user,today.minusDays(29),"보관할 식사",null);
        retention.deleteExpired(today);
        assertEquals(0L,db.queryForObject("select count(*) from meal_records where id=?",Long.class,expired));
        assertEquals(0L,db.queryForObject("select count(*) from meal_record_items where meal_record_id=?",Long.class,expired));
        assertEquals(1L,db.queryForObject("select count(*) from meal_records where id=?",Long.class,retained));
        assertEquals(1L,db.queryForObject("select count(*) from meal_recommendations where id=?",Long.class,recommendation));
        assertEquals(1L,db.queryForObject("select count(*) from recipes where id=?",Long.class,recipe));
        retention.deleteExpired(today);
        assertEquals(1L,db.queryForObject("select count(*) from meal_records where id=?",Long.class,retained));
    }
    @Test void startupAndDailyCleanupTriggerTheSameThirtyDayPolicy() {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        long first=stored(user,today.minusDays(30),"오래된 식사",null);
        scheduler.onStartup();
        assertEquals(0L,db.queryForObject("select count(*) from meal_records where id=?",Long.class,first));
        long second=stored(user,today.minusDays(30),"다른 오래된 식사",null);
        scheduler.daily();
        assertEquals(0L,db.queryForObject("select count(*) from meal_records where id=?",Long.class,second));
    }
    @Test void recentHistoryUsesOnlyOwnersActualMealsInLast14Days() {
        long user=user(), other=user(); LocalDate today=MealRecordPolicy.today();
        stored(user,today,"오늘",null); stored(user,today.minusDays(13),"14일 경계",null);
        stored(user,today.minusDays(14),"15일 전",null); stored(user,today.plusDays(1),"내일",null); stored(other,today,"다른 사용자",null);
        var result=history.recentAt(user,today);
        assertEquals(2,result.size());
        assertEquals(List.of("오늘"),result.get(0).foods());
        assertEquals(List.of("14일 경계"),result.get(1).foods());
    }
    @Test void userCannotCreateOrReadExpiredRecords() {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        var old=new MealRecordDtos.DirectRequest(today.minusDays(30),"DINNER",null,List.of(new MealRecordDtos.FoodInput("밥",null)));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,() -> records.createDirect(user,old)).getStatusCode());
        stored(user,today.minusDays(30),"오래된 식사",null);
        assertTrue(records.list(user,null,null).isEmpty());
        assertTrue(records.list(user,today.minusDays(40),today.minusDays(30)).isEmpty());
    }
}
