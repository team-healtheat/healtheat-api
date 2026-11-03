package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.health.HealthProfileService;
import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.preferences.PreferenceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MealRegenerationService {
    private static final Set<String> TYPES=Set.of("BREAKFAST","LUNCH","DINNER");
    private final EntityManager entities;
    private final MealPlanQueryService query;
    private final MealPlanGenerationService storage;
    private final PreferenceService preferences;
    private final HealthProfileService health;
    private final MealGenerationClient client;
    private final MealPlanValidator validator;
    MealRegenerationService(EntityManager entities, MealPlanQueryService query, MealPlanGenerationService storage,
                            PreferenceService preferences, HealthProfileService health, MealGenerationClient client, MealPlanValidator validator) {
        this.entities=entities; this.query=query; this.storage=storage; this.preferences=preferences; this.health=health; this.client=client; this.validator=validator;
    }
    @Transactional
    public MealPlanDtos.Plan regenerate(long userId, long planId, String type, String request) {
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown meal type");
        if (entities.find(User.class,userId,LockModeType.PESSIMISTIC_WRITE)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        var plan=entities.find(MealPlan.class,planId);
        if (plan==null || !plan.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Meal plan not found");
        var current=entities.createQuery("select r from MealRecommendation r where r.mealPlanId = :id and r.current = true order by r.id",MealRecommendation.class)
            .setParameter("id",planId).getResultList();
        var old=current.stream().filter(r -> type.equals(r.mealType)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Meal not found"));
        int otherDining=(int)current.stream().filter(r -> !type.equals(r.mealType) && "DINING_OUT".equals(r.sourceType)).count();
        var oldNames=entities.createQuery("select i.foodName from MealItem i where i.recommendationId = :id order by i.id",String.class).setParameter("id",old.id).getResultList();
        var preference=preferences.get(userId);
        try { validator.validateEligibility(preference.allowDiningOut(),preference.allergyStatus(),preference.allergens()); }
        catch (MealPlanValidator.InvalidMealPlanException ex) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,ex.getMessage()); }
        var factors=health.get(userId).factors().stream().filter(f -> f.active())
            .map(f -> new MealGenerationContext.HealthFactor(f.name(),f.type(),f.reportedStatus(),f.supported())).toList();
        var context=new MealGenerationContext(preference.allowDiningOut(),preference.likedFoods(),preference.dislikedFoods(),preference.dietaryPreferences(),factors,preference.allergyStatus(),preference.allergens());
        var generated=client.regenerate(new MealRegenerationContext(context,type,request,oldNames,otherDining));
        try { validator.validateReplacement(generated,type,preference.allowDiningOut(),otherDining); }
        catch (MealPlanValidator.InvalidMealPlanException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"생성된 식단을 검증하지 못했습니다."); }
        old.current=false;
        entities.flush();
        storage.saveRecommendation(planId,generated,old.version+1);
        entities.flush();
        return query.get(userId,plan.planDate);
    }
}
