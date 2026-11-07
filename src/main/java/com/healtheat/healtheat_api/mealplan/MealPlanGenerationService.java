package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import com.healtheat.healtheat_api.mealrecord.MealHistoryQueryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MealPlanGenerationService {
    private final EntityManager entities;
    private final MealPlanQueryService query;
    private final PreferenceService preferences;
    private final HealthProfileService health;
    private final MealGenerationClient client;
    private final MealPlanValidator validator;
    private final MealHistoryQueryService mealHistory;
    MealPlanGenerationService(EntityManager entities, MealPlanQueryService query, PreferenceService preferences,
                              HealthProfileService health, MealGenerationClient client, MealPlanValidator validator, MealHistoryQueryService mealHistory) {
        this.entities=entities; this.query=query; this.preferences=preferences; this.health=health; this.client=client; this.validator=validator; this.mealHistory=mealHistory;
    }
    @Transactional
    public MealPlanDtos.Plan generate(long userId, LocalDate date) {
        if (entities.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        var existing=query.get(userId,date);
        if (existing != null) return existing;
        var preference=preferences.get(userId);
        var activeHealth=health.get(userId).factors().stream().filter(f -> f.active())
            .map(f -> new MealGenerationContext.HealthFactor(f.name(), f.type(), f.reportedStatus(), f.supported())).toList();
        try { validator.validateEligibility(preference.allowDiningOut(),preference.allergyStatus(),preference.allergens()); }
        catch (MealPlanValidator.InvalidMealPlanException ex) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage()); }
        var context=new MealGenerationContext(preference.allowDiningOut(),preference.likedFoods(),preference.dislikedFoods(),preference.dietaryPreferences(),activeHealth,preference.allergyStatus(),preference.allergens(),mealHistory.recent(userId),recentDiningOut(userId,date),recentRecommendedFoods(userId,date));
        var generated=client.generate(context);
        try { validator.validate(generated,preference.allowDiningOut(),preference.allergyStatus(),preference.allergens()); }
        catch (MealPlanValidator.InvalidMealPlanException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "생성된 식단을 검증하지 못했습니다."); }
        var plan=new MealPlan(); plan.userId=userId; plan.planDate=date; entities.persist(plan);
        for (var meal: generated.meals()) saveRecommendation(plan.id, meal, 1);
        entities.flush();
        return query.get(userId,date);
    }
    java.util.List<String> recentRecommendedFoods(long userId, LocalDate date) {
        return entities.createQuery("""
            select distinct i.foodName from MealItem i, MealRecommendation r, MealPlan p
            where i.recommendationId=r.id and r.mealPlanId=p.id and p.userId=:userId
              and p.planDate>=:fromDate and p.planDate<=:date and i.foodName is not null
            order by i.foodName
            """,String.class).setParameter("userId",userId).setParameter("fromDate",date.minusDays(13))
            .setParameter("date",date).setMaxResults(150).getResultList();
    }
    long recentDiningOut(long userId,LocalDate date) {
        return entities.createQuery("select count(r.id) from MealRecommendation r, MealPlan p where r.mealPlanId=p.id and p.userId=:userId and p.planDate>=:from and p.planDate<:date and r.current=true and r.sourceType='DINING_OUT'",Long.class)
            .setParameter("userId",userId).setParameter("from",date.minusDays(6)).setParameter("date",date).getSingleResult();
    }
    void saveRecommendation(long planId, GeneratedMealPlan.Meal meal, int version) {
        var rec=new MealRecommendation(); rec.mealPlanId=planId; rec.mealType=meal.mealType(); rec.sourceType=meal.sourceType();
        rec.recommendationReason=meal.recommendationReason(); rec.current=true; rec.version=version; entities.persist(rec);
        for (var generatedItem: meal.items()) {
            var item=new MealItem(); item.recommendationId=rec.id; item.foodName=generatedItem.foodName(); item.foodRole=generatedItem.foodRole();
            item.preparationType=generatedItem.preparationType(); item.servingDescription=generatedItem.servingDescription(); item.explicitIngredients=generatedItem.explicitIngredients(); entities.persist(item);
            if (generatedItem.recipe()!=null) saveRecipe(item.id,generatedItem.recipe());
        }
    }
    private void saveRecipe(long itemId, GeneratedMealPlan.Recipe generated) {
        var recipe=new MealRecipe(); recipe.mealItemId=itemId; recipe.title=generated.title(); recipe.servings=generated.servings(); recipe.cookingTimeMinutes=generated.cookingTimeMinutes(); entities.persist(recipe);
        for (var ingredient: generated.ingredients()) {
            var row=new MealRecipeIngredient(); row.recipeId=recipe.id; row.name=ingredient.name(); row.unit=ingredient.unit();
            if (ingredient.quantity()!=null && !ingredient.quantity().isBlank()) {
                try { row.quantity=new BigDecimal(ingredient.quantity()); }
                catch (NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"생성된 재료 분량이 올바르지 않습니다."); }
                if (row.quantity.signum()<0) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"생성된 재료 분량이 올바르지 않습니다.");
            }
            entities.persist(row);
        }
        int number=0;
        for (var instruction: generated.steps()) {
            var step=new MealRecipeStep(); step.recipeId=recipe.id; step.stepNumber=++number; step.instruction=instruction; entities.persist(step);
        }
    }
}
