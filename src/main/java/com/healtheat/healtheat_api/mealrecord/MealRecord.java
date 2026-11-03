package com.healtheat.healtheat_api.mealrecord;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
@Entity @Table(name="meal_records")
class MealRecord {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="user_id",nullable=false) Long userId;
    @Column(name="meal_date",nullable=false) LocalDate mealDate;
    @Column(name="meal_type",nullable=false) String mealType;
    @Column(name="source_type") String sourceType;
    @Column(name="from_recommendation") Boolean fromRecommendation;
    @Column(name="recommendation_id") Long recommendationId;
    @Column(name="created_at",insertable=false,updatable=false) Instant createdAt;
    protected MealRecord() {}
}
