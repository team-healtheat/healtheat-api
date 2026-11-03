package com.healtheat.healtheat_api.health;
import jakarta.persistence.*;
@Entity @Table(name="health_factors")
class HealthFactor {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    String code;
    String name;
    String type;
    String supported;
    protected HealthFactor() {}
    HealthFactor(String code, String name, String type) { this.code=code; this.name=name; this.type=type; this.supported="UNSUPPORTED"; }
}
