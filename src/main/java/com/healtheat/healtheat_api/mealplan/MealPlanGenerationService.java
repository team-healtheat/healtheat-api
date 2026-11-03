package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
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
    MealPlanGenerationService(EntityManager entities, MealPlanQueryService query, PreferenceService preferences,
                              HealthProfileService health, MealGenerationClient client, MealPlanValidator validator) {
        this.entities=entities; this.query=query; this.preferences=preferences; this.health=health; this.client=client; this.validator=validator;
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
        var context=new MealGenerationContext(preference.allowDiningOut(),preference.likedFoods(),preference.dislikedFoods(),preference.dietaryPreferences(),activeHealth,preference.allergyStatus(),preference.allergens());
        var generated=client.generate(context);
        try { validator.validate(generated,preference.allowDiningOut(),preference.allergyStatus(),preference.allergens()); }
        catch (MealPlanValidator.InvalidMealPlanException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "생성된 식단을 검증하지 못했습니다."); }
        var plan=new MealPlan(); plan.userId=userId; plan.planDate=date; entities.persist(plan);
        for (var meal: generated.meals()) saveRecommendation(plan.id, meal, 1);
        entities.flush();
        return query.get(userId,date);
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
