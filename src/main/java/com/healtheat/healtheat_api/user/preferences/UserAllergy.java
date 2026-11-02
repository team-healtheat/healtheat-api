package com.healtheat.healtheat_api.user.preferences;

import jakarta.persistence.*;

@Entity
@Table(name = "user_allergies")
class UserAllergy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "user_id", nullable = false) Long userId;
    @Column(name = "allergen_name") String allergenName;
    String status;
    protected UserAllergy() {}
    UserAllergy(long userId, String allergenName) { this.userId = userId; this.allergenName = allergenName; this.status = "PRESENT"; }
}
