package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.*;
import java.time.LocalDate;
@Entity @Table(name = "meal_plans")
class MealPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "user_id", nullable = false) Long userId;
    @Column(name = "plan_date", nullable = false) LocalDate planDate;
    protected MealPlan() {}
}
