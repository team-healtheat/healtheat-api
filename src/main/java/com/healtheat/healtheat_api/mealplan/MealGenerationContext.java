package com.healtheat.healtheat_api.mealplan;

import java.util.List;

/** Only relevant preferences and user-confirmed health factors; no user identifiers or raw text. */
public record MealGenerationContext(Boolean allowDiningOut, List<String> likedFoods,
                                    List<String> dislikedFoods, List<String> dietaryPreferences,
                                    List<HealthFactor> healthFactors, List<String> allergens) {
    public record HealthFactor(String name, String type, String reportedStatus, String supportStatus) {}
}
