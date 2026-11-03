package com.healtheat.healtheat_api.health;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="user_health_factors")
class UserHealthFactor {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="user_id", nullable=false) Long userId;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="health_factor_id") HealthFactor factor;
    @Column(name="reported_status") String reportedStatus;
    @Column(name="original_text") String originalText;
    @Column(name="is_active") Boolean active;
    @Column(name="created_at", insertable=false, updatable=false) Instant createdAt;
    @Column(name="updated_at", insertable=false) Instant updatedAt;
    protected UserHealthFactor() {}
    UserHealthFactor(long userId, HealthFactor factor) { this.userId=userId; this.factor=factor; }
}
