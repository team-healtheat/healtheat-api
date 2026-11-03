package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.mealrecord.MealRecordPolicy;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MealPlanRetentionTests {
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired MealPlanRetentionService retention;
    @Autowired MealPlanRetentionScheduler scheduler;

    private long user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "hash")).getId(); }
    private long plan(long user, LocalDate date) {
        return db.queryForObject("insert into meal_plans(user_id,plan_date) values (?,?) returning id", Long.class, user, date);
    }
    private long recommendation(long plan, boolean current, int version) {
        return db.queryForObject("insert into meal_recommendations(meal_plan_id,meal_type,is_current,version) values (?,'LUNCH',?,?) returning id", Long.class, plan, current, version);
    }
    private long recipe(long recommendation) {
        Long item=db.queryForObject("insert into meal_items(meal_recommendation_id,food_name) values (?,'가상 음식') returning id",Long.class,recommendation);
        Long recipe=db.queryForObject("insert into recipes(meal_item_id,title) values (?,'가상 레시피') returning id",Long.class,item);
        db.update("insert into recipe_ingredients(recipe_id,ingredient_name) values (?,'재료')",recipe);
        db.update("insert into recipe_steps(recipe_id,step_number,instruction) values (?,1,'조리')",recipe);
        return recipe;
    }
    private long count(String sql,long id) { return db.queryForObject(sql,Long.class,id); }

    @Test void removesOldPlansIncludingAllRecommendationsAndRecipesButKeepsBoundary() {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        long old=plan(user,today.minusDays(30)), boundary=plan(user,today.minusDays(29));
        long oldVersion=recommendation(old,false,1), currentVersion=recommendation(old,true,2);
        long oldRecipe=recipe(oldVersion), currentRecipe=recipe(currentVersion);
        long retainedRecipe=recipe(recommendation(boundary,true,1));
        retention.deleteExpired(today);
        assertEquals(0,count("select count(*) from meal_plans where id=?",old));
        assertEquals(0,count("select count(*) from meal_recommendations where meal_plan_id=?",old));
        for (long id:new long[]{oldRecipe,currentRecipe}) {
            assertEquals(0,count("select count(*) from recipes where id=?",id));
            assertEquals(0,count("select count(*) from recipe_ingredients where recipe_id=?",id));
            assertEquals(0,count("select count(*) from recipe_steps where recipe_id=?",id));
        }
        assertEquals(1,count("select count(*) from meal_plans where id=?",boundary));
        assertEquals(1,count("select count(*) from recipes where id=?",retainedRecipe));
        retention.deleteExpired(today);
        assertEquals(1,count("select count(*) from recipes where id=?",retainedRecipe));
    }

    @Test void preservesReferencedRecommendationsAndFoodRecordsUntilReferenceIsRemoved() {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        long old=plan(user,today.minusDays(30)); long recommendation=recommendation(old,true,1); long recipe=recipe(recommendation);
        long record=db.queryForObject("insert into meal_records(user_id,meal_date,meal_type,from_recommendation,recommendation_id) values (?,?,'LUNCH',true,?) returning id",Long.class,user,today,recommendation);
        db.update("insert into meal_record_items(meal_record_id,food_name) values (?,'보존할 음식')",record);
        retention.deleteExpired(today);
        assertEquals(1,count("select count(*) from meal_plans where id=?",old));
        assertEquals(1,count("select count(*) from recipes where id=?",recipe));
        assertEquals(1,count("select count(*) from meal_record_items where meal_record_id=?",record));
        db.update("delete from meal_record_items where meal_record_id=?",record);
        db.update("delete from meal_records where id=?",record);
        retention.deleteExpired(today);
        assertEquals(0,count("select count(*) from meal_plans where id=?",old));
    }

    @Test void startupAndDailyCleanupApplyThePolicy() {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        long first=plan(user,today.minusDays(30));
        scheduler.onStartup();
        assertEquals(0,count("select count(*) from meal_plans where id=?",first));
        long second=plan(user,today.minusDays(31));
        scheduler.daily();
        assertEquals(0,count("select count(*) from meal_plans where id=?",second));
    }

    @Test void skipsBusyRecommendationAndDeletesOnLaterRun() throws Exception {
        long user=user(); LocalDate today=MealRecordPolicy.today();
        long old=plan(user,today.minusDays(30)); long recommendation=recommendation(old,true,1);
        try (var connection=db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("select id from meal_recommendations where id=? for update")) {
                statement.setLong(1,recommendation);
                try (var rows=statement.executeQuery()) { assertTrue(rows.next()); }
            }
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),() -> retention.deleteExpired(today));
            assertEquals(1,count("select count(*) from meal_plans where id=?",old));
            connection.rollback();
        }
        retention.deleteExpired(today);
        assertEquals(0,count("select count(*) from meal_plans where id=?",old));
    }
}
