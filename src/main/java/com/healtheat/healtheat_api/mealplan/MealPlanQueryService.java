package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
@Service
class MealPlanQueryService {
    private final EntityManager entities;
    MealPlanQueryService(EntityManager entities) { this.entities = entities; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MealPlanDtos.Plan get(long userId, LocalDate date) {
        var plans = entities.createQuery("select p from MealPlan p where p.userId = :userId and p.planDate = :date", MealPlan.class)
            .setParameter("userId", userId).setParameter("date", date).getResultList();
        if (plans.isEmpty()) return null;
        var plan = plans.get(0);
        var recommendations = entities.createQuery("select r from MealRecommendation r where r.mealPlanId = :id and r.current = true order by r.id", MealRecommendation.class)
            .setParameter("id", plan.id).getResultList();
        var meals = recommendations.stream().map(r -> {
            var items = entities.createQuery("select i from MealItem i where i.recommendationId = :id order by i.id", MealItem.class)
                .setParameter("id", r.id).getResultList().stream()
                .map(i -> new MealPlanDtos.Item(i.id, i.foodName, i.foodRole, i.preparationType, i.servingDescription)).toList();
            return new MealPlanDtos.Meal(r.id, r.mealType, r.sourceType, r.recommendationReason, r.version, items);
        }).toList();
        return new MealPlanDtos.Plan(plan.id, plan.planDate, meals);
    }
}
