package com.healtheat.healtheat_api.chat;

import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.mealrecord.MealHistoryQueryService;
import com.healtheat.healtheat_api.user.preferences.PreferenceDtos;
import java.util.List;

public interface ChatModel {
    enum Intent { FOOD_CONSULTATION, GET_MEAL_HISTORY, GET_HEALTH_PROFILE, ADD_HEALTH_FACTOR, UPDATE_HEALTH_FACTOR, REMOVE_HEALTH_FACTOR, REGENERATE_MEAL, GENERAL_CHAT }
    record Classification(Intent intent, String foodName, Integer periodDays, String mealType, String additionalRequest, Long targetFactorId) {}
    record Factor(Long factorId, String name, String type, String reportedStatus, boolean active) {}
    record Turn(String role, String content) {}
    record AdviceContext(String userMessage, List<Factor> healthFactors, PreferenceDtos.AllergyStatus allergyStatus,
                         List<String> allergens, List<MealHistoryQueryService.RecentMeal> recentMeals,
                         int actualDays, boolean limitedToRetention, List<Turn> conversation) {}
    Classification classify(String message, List<Factor> healthFactors, List<Turn> conversation);
    String answer(AdviceContext context);
}
