package com.healtheat.healtheat_api.mealplan;

import java.util.List;

/** Contains only information needed for generation; never includes user identifiers. */
public record MealGenerationContext(Boolean allowDiningOut, List<String> likedFoods,
                                    List<String> dislikedFoods, List<String> dietaryPreferences) {}
