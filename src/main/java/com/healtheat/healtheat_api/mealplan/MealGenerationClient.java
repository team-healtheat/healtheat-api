package com.healtheat.healtheat_api.mealplan;

public interface MealGenerationClient {
    GeneratedMealPlan.Plan generate(MealGenerationContext context);
    GeneratedMealPlan.Meal regenerate(MealRegenerationContext context);
}
