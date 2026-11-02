package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.user.preferences.PreferenceDtos.AllergyStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Reject incomplete or unverifiable responses; never treat model output as an allergy safety guarantee. */
@Component
public class MealPlanValidator {
    private static final Set<String> MEALS = Set.of("BREAKFAST", "LUNCH", "DINNER");
    private static final Set<String> SOURCES = Set.of("HOME_COOKED", "DINING_OUT");
    private static final Set<String> ROLES = Set.of("STAPLE", "SOUP", "MAIN", "SIDE", "OTHER");
    private static final Set<String> PREPARATIONS = Set.of("COOK", "PREPARE", "READY_TO_EAT");

    public void validate(GeneratedMealPlan.Plan plan, Boolean allowDiningOut, AllergyStatus allergyStatus,
                         List<String> allergens, boolean hasUnverifiedHealthGuidance) {
        validateEligibility(allowDiningOut, allergyStatus, allergens, hasUnverifiedHealthGuidance);
        if (plan == null || plan.meals() == null || plan.meals().size() != 3) fail("세 끼의 식단이 필요합니다.");
        Set<String> seen = new HashSet<>();
        int diningOut = 0;
        for (var meal : plan.meals()) {
            if (meal == null || (meal.mealType() == null || !MEALS.contains(meal.mealType())) || !seen.add(meal.mealType())) fail("식사 종류가 올바르지 않습니다.");
            if ((meal.sourceType() == null || !SOURCES.contains(meal.sourceType()))) fail("식사 출처가 올바르지 않습니다.");
            if ("DINING_OUT".equals(meal.sourceType())) diningOut++;
            if (blank(meal.recommendationReason()) || meal.items() == null || meal.items().isEmpty() || meal.items().size() > 10)
                fail("식사 구성이나 추천 이유가 올바르지 않습니다.");
            for (var item : meal.items()) {
                if (item == null || blank(item.foodName()) || (item.foodRole() == null || !ROLES.contains(item.foodRole())) || (item.preparationType() == null || !PREPARATIONS.contains(item.preparationType())))
                    fail("음식 정보가 올바르지 않습니다.");
                if ("HOME_COOKED".equals(meal.sourceType()) && !"READY_TO_EAT".equals(item.preparationType())) validateRecipe(item.recipe());
                else if (item.recipe() != null) validateRecipe(item.recipe());
            }
        }
        if (diningOut > 1 || (diningOut > 0 && !allowDiningOut)) fail("외식 허용 횟수를 초과했습니다.");
    }
    public void validateEligibility(Boolean allowDiningOut, AllergyStatus allergyStatus, List<String> allergens, boolean hasUnverifiedHealthGuidance) {
        if (allowDiningOut == null) fail("외식 허용 여부를 먼저 확인해야 합니다.");
        if (allergyStatus != AllergyStatus.NONE || allergens == null || !allergens.isEmpty())
            fail("알레르기 정보가 없다고 확인되지 않았거나 재료 검증이 아직 지원되지 않습니다.");
        if (hasUnverifiedHealthGuidance) fail("건강 정보에 적용할 수 있는 검증된 지침이 필요합니다.");
    }
    private void validateRecipe(GeneratedMealPlan.Recipe recipe) {
        if (recipe == null || blank(recipe.title()) || recipe.servings() == null || recipe.servings() < 1
            || recipe.cookingTimeMinutes() == null || recipe.cookingTimeMinutes() < 0
            || recipe.ingredients() == null || recipe.ingredients().isEmpty() || recipe.ingredients().size() > 50
            || recipe.steps() == null || recipe.steps().isEmpty() || recipe.steps().size() > 50)
            fail("레시피가 불완전합니다.");
        for (var ingredient : recipe.ingredients()) if (ingredient == null || blank(ingredient.name())) fail("재료 이름이 필요합니다.");
        for (String step : recipe.steps()) if (blank(step)) fail("조리 단계가 비어 있습니다.");
    }
    private boolean blank(String value) { return value == null || value.isBlank() || value.length() > 1000; }
    private void fail(String message) { throw new InvalidMealPlanException(message); }
    public static class InvalidMealPlanException extends RuntimeException {
        public InvalidMealPlanException(String message) { super(message); }
    }
}
