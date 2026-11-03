package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="recipe_ingredients")
class MealRecipeIngredient {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="recipe_id",nullable=false) Long recipeId;
    @Column(name="ingredient_name") String name;
    BigDecimal quantity;
    String unit;
    protected MealRecipeIngredient() {}
}
