package com.healtheat.healtheat_api.mealplan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
public final class MealPlanDtos {
    private MealPlanDtos() {}
    public record Ingredient(String name, BigDecimal quantity, String unit) {}
    public record Recipe(Long id, String title, Integer servings, Integer cookingTimeMinutes, List<Ingredient> ingredients, List<String> steps) {}
    public record Item(Long id, String foodName, String foodRole, String preparationType, String servingDescription, Recipe recipe, List<String> explicitIngredients) {}
    public record Meal(Long id, String mealType, String sourceType, String recommendationReason, int version, List<Item> items) {}
    public record Plan(Long id, LocalDate planDate, List<Meal> meals, String allergyNotice) {}
}
