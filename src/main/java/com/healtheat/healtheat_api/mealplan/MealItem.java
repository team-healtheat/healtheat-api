package com.healtheat.healtheat_api.mealplan;
import jakarta.persistence.*;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name = "meal_items")
class MealItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "meal_recommendation_id", nullable = false) Long recommendationId;
    @Column(name = "food_name") String foodName;
    @Column(name = "food_role") String foodRole;
    @Column(name = "preparation_type") String preparationType;
    @Column(name = "serving_description") String servingDescription;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "explicit_ingredients", columnDefinition = "jsonb") List<String> explicitIngredients;
    protected MealItem() {}
}
