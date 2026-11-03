package com.healtheat.healtheat_api.mealrecord;
import jakarta.persistence.*;
@Entity @Table(name="meal_record_items")
class MealRecordItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="meal_record_id",nullable=false) Long mealRecordId;
    @Column(name="food_name") String foodName;
    @Column(name="serving_description") String servingDescription;
    protected MealRecordItem() {}
}
