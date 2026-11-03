package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.*;
@Entity @Table(name="recipe_steps")
class MealRecipeStep {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="recipe_id",nullable=false) Long recipeId;
    @Column(name="step_number",nullable=false) int stepNumber;
    String instruction;
    protected MealRecipeStep() {}
}
