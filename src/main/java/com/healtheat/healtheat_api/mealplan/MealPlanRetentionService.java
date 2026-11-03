package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.mealrecord.MealRecordPolicy;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MealPlanRetentionService {
    private final JdbcTemplate db;
    MealPlanRetentionService(JdbcTemplate db) { this.db = db; }

    @Transactional
    public int deleteExpired(LocalDate today) {
        var cutoff = MealRecordPolicy.retentionStart(today);
        var plans = db.queryForList("""
            select p.id from meal_plans p
            where p.plan_date < ? and not exists (
                select 1 from meal_recommendations r join meal_records mr on mr.recommendation_id = r.id
                where r.meal_plan_id = p.id
            ) order by p.id for update of p skip locked
            """, Long.class, cutoff);
        int deleted = 0;
        for (long planId : plans) {
            // Skip busy recommendations to avoid blocking an in-flight regeneration or record insert.
            var locked = db.queryForList("select id from meal_recommendations where meal_plan_id = ? order by id for update skip locked", Long.class, planId);
            Long total = db.queryForObject("select count(*) from meal_recommendations where meal_plan_id = ?", Long.class, planId);
            if (total == null || locked.size() != total) continue;
            Long references = db.queryForObject("""
                select count(*) from meal_records mr join meal_recommendations r on r.id = mr.recommendation_id
                where r.meal_plan_id = ?
                """, Long.class, planId);
            if (references != null && references > 0) continue;
            String recipeIds = "select rc.id from recipes rc join meal_items i on i.id=rc.meal_item_id "
                + "join meal_recommendations r on r.id=i.meal_recommendation_id where r.meal_plan_id=?";
            db.update("delete from recipe_steps where recipe_id in (" + recipeIds + ")", planId);
            db.update("delete from recipe_ingredients where recipe_id in (" + recipeIds + ")", planId);
            db.update("delete from recipes where id in (" + recipeIds + ")", planId);
            db.update("delete from meal_items where meal_recommendation_id in (select id from meal_recommendations where meal_plan_id=?)", planId);
            db.update("delete from meal_recommendations where meal_plan_id=?", planId);
            deleted += db.update("delete from meal_plans where id=?", planId);
        }
        return deleted;
    }
}
