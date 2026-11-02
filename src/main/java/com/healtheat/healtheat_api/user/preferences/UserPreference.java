package com.healtheat.healtheat_api.user.preferences;

import jakarta.persistence.*;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_preferences")
class UserPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "user_id", nullable = false, unique = true) Long userId;
    @Column(name = "allow_dining_out") Boolean allowDiningOut;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "liked_foods", columnDefinition = "jsonb") List<String> likedFoods;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "disliked_foods", columnDefinition = "jsonb") List<String> dislikedFoods;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "dietary_preferences", columnDefinition = "jsonb") List<String> dietaryPreferences;
    @Enumerated(EnumType.STRING) @Column(name = "allergy_status", nullable = false) PreferenceDtos.AllergyStatus allergyStatus = PreferenceDtos.AllergyStatus.UNANSWERED;
    protected UserPreference() {}
    UserPreference(long userId) { this.userId = userId; }
}
