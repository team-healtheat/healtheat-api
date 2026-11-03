package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.*;
@Entity @Table(name="recipes")
class MealRecipe {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="meal_item_id",nullable=false,unique=true) Long mealItemId;
    String title;
    Integer servings;
    @Column(name="cooking_time_minutes") Integer cookingTimeMinutes;
    protected MealRecipe() {}
}
