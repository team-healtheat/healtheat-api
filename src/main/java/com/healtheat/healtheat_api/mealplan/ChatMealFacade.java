package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.mealrecord.MealRecordPolicy;
import org.springframework.stereotype.Service;

@Service
public class ChatMealFacade {
    private final MealPlanQueryService plans;
    private final MealRegenerationService regeneration;
    ChatMealFacade(MealPlanQueryService plans,MealRegenerationService regeneration) { this.plans=plans; this.regeneration=regeneration; }
    public MealPlanDtos.Plan regenerateToday(long userId,String mealType,String additionalRequest) {
        var today=plans.get(userId,MealRecordPolicy.today());
        if (today==null || today.meals().stream().noneMatch(m -> mealType.equals(m.mealType()))) return null;
        return regeneration.regenerate(userId,today.id(),mealType,additionalRequest);
    }
}
