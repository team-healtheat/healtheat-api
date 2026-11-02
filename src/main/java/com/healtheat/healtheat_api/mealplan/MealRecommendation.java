package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.*;
@Entity @Table(name = "meal_recommendations")
class MealRecommendation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "meal_plan_id", nullable = false) Long mealPlanId;
    @Column(name = "meal_type", nullable = false) String mealType;
    @Column(name = "source_type") String sourceType;
    @Column(name = "recommendation_reason") String recommendationReason;
    @Column(name = "is_current", nullable = false) boolean current;
    int version;
    protected MealRecommendation() {}
}
