package com.healtheat.healtheat_api.mealplan;
import java.time.LocalDate;
import java.util.List;
public final class MealPlanDtos {
    private MealPlanDtos() {}
    public record Item(Long id, String foodName, String foodRole, String preparationType, String servingDescription) {}
    public record Meal(Long id, String mealType, String sourceType, String recommendationReason, int version, List<Item> items) {}
    public record Plan(Long id, LocalDate planDate, List<Meal> meals) {}
}
