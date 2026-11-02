package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.user.preferences.PreferenceDtos.AllergyStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MealPlanValidatorTests {
    private final MealPlanValidator validator = new MealPlanValidator();
    private GeneratedMealPlan.Item item() {
        var recipe = new GeneratedMealPlan.Recipe("밥", 1, 20,
            List.of(new GeneratedMealPlan.Ingredient("쌀", "1", "공기")), List.of("밥을 짓는다"));
        return new GeneratedMealPlan.Item("밥", "STAPLE", "COOK", "한 공기", recipe);
    }
    private GeneratedMealPlan.Meal meal(String type, String source) {
        return new GeneratedMealPlan.Meal(type, source, "식사", List.of(item()));
    }
    private GeneratedMealPlan.Plan plan(String source) {
        return new GeneratedMealPlan.Plan(List.of(meal("BREAKFAST", "HOME_COOKED"), meal("LUNCH", source), meal("DINNER", "HOME_COOKED")));
    }
    @Test void acceptsThreeDistinctMealsAndOneAllowedDiningOut() {
        assertDoesNotThrow(() -> validator.validate(plan("DINING_OUT"), true, AllergyStatus.NONE, List.of()));
    }
    @Test void rejectsUnverifiedAllergyAndHealthInformation() {
        for (var status : List.of(AllergyStatus.UNANSWERED, AllergyStatus.UNKNOWN, AllergyStatus.PRESENT))
            assertThrows(MealPlanValidator.InvalidMealPlanException.class, () -> validator.validate(plan("HOME_COOKED"), false, status, List.of()));
        assertThrows(MealPlanValidator.InvalidMealPlanException.class, () -> validator.validate(plan("HOME_COOKED"), false, AllergyStatus.NONE, List.of("새우")));
    }
    @Test void rejectsMissingOrDuplicateMealsAndForbiddenDiningOut() {
        assertThrows(MealPlanValidator.InvalidMealPlanException.class, () -> validator.validate(plan("DINING_OUT"), false, AllergyStatus.NONE, List.of()));
        var duplicate = new GeneratedMealPlan.Plan(List.of(meal("BREAKFAST", "HOME_COOKED"), meal("BREAKFAST", "HOME_COOKED"), meal("DINNER", "HOME_COOKED")));
        assertThrows(MealPlanValidator.InvalidMealPlanException.class, () -> validator.validate(duplicate, true, AllergyStatus.NONE, List.of()));
        assertThrows(MealPlanValidator.InvalidMealPlanException.class, () -> validator.validate(new GeneratedMealPlan.Plan(null), true, AllergyStatus.NONE, List.of()));
    }
    @Test void rejectsMissingHomeRecipe() {
        var noRecipe = new GeneratedMealPlan.Item("밥", "STAPLE", "COOK", null, null);
        var malformed = new GeneratedMealPlan.Plan(List.of(new GeneratedMealPlan.Meal("BREAKFAST", "HOME_COOKED", "식사", List.of(noRecipe)), meal("LUNCH", "HOME_COOKED"), meal("DINNER", "HOME_COOKED")));
        assertThrows(MealPlanValidator.InvalidMealPlanException.class, () -> validator.validate(malformed, false, AllergyStatus.NONE, List.of()));
    }
}
