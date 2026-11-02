package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
@Service
class MealPlanQueryService {
    private final EntityManager entities;
    MealPlanQueryService(EntityManager entities) { this.entities = entities; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MealPlanDtos.Plan get(long userId, LocalDate date) {
        var plans = entities.createQuery("select p from MealPlan p where p.userId = :userId and p.planDate = :date", MealPlan.class)
            .setParameter("userId", userId).setParameter("date", date).getResultList();
        if (plans.isEmpty()) return null;
        var plan = plans.get(0);
        var recommendations = entities.createQuery("select r from MealRecommendation r where r.mealPlanId = :id and r.current = true order by r.id", MealRecommendation.class)
            .setParameter("id", plan.id).getResultList();
        var meals = recommendations.stream().map(r -> {
            var items = entities.createQuery("select i from MealItem i where i.recommendationId = :id order by i.id", MealItem.class)
                .setParameter("id", r.id).getResultList().stream()
                .map(i -> new MealPlanDtos.Item(i.id, i.foodName, i.foodRole, i.preparationType, i.servingDescription, recipe(i.id), i.explicitIngredients)).toList();
            return new MealPlanDtos.Meal(r.id, r.mealType, r.sourceType, r.recommendationReason, r.version, items);
        }).toList();
        return new MealPlanDtos.Plan(plan.id, plan.planDate, meals, "AI가 작성한 재료 정보는 불완전할 수 있습니다. 숨은 재료와 교차접촉은 확인할 수 없으므로 섭취 전 직접 확인하세요.");
    }
    private MealPlanDtos.Recipe recipe(long itemId) {
        var recipes = entities.createQuery("select r from MealRecipe r where r.mealItemId = :id", MealRecipe.class).setParameter("id", itemId).getResultList();
        if (recipes.isEmpty()) return null;
        var r=recipes.get(0);
        var ingredients=entities.createQuery("select i from MealRecipeIngredient i where i.recipeId = :id order by i.id", MealRecipeIngredient.class)
            .setParameter("id",r.id).getResultList().stream().map(i -> new MealPlanDtos.Ingredient(i.name,i.quantity,i.unit)).toList();
        var steps=entities.createQuery("select s.instruction from MealRecipeStep s where s.recipeId = :id order by s.stepNumber",String.class).setParameter("id",r.id).getResultList();
        return new MealPlanDtos.Recipe(r.id,r.title,r.servings,r.cookingTimeMinutes,ingredients,steps);
    }
}
