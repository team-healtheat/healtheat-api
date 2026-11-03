package com.healtheat.healtheat_api.mealplan;

import java.util.List;

public record MealRegenerationContext(MealGenerationContext preferences, String mealType,
                                      String additionalRequest, List<String> previousFoodNames,
                                      int otherDiningOutMeals) {}
