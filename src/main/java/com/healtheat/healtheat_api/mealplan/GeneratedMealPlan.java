package com.healtheat.healtheat_api.mealplan;

import java.util.List;

/** Structured AI output is untrusted until validated by the server. */
public final class GeneratedMealPlan {
    private GeneratedMealPlan() {}
    public record Ingredient(String name, String quantity, String unit) {}
    public record Recipe(String title, Integer servings, Integer cookingTimeMinutes,
                         List<Ingredient> ingredients, List<String> steps) {}
    public record Item(String foodName, String foodRole, String preparationType,
                       String servingDescription, Recipe recipe, List<String> explicitIngredients) {
        public Item(String foodName, String foodRole, String preparationType, String servingDescription, Recipe recipe) {
            this(foodName, foodRole, preparationType, servingDescription, recipe, null);
        }
    }
    public record Meal(String mealType, String sourceType, String recommendationReason,
                       List<Item> items) {}
    public record Plan(List<Meal> meals) {}
}
