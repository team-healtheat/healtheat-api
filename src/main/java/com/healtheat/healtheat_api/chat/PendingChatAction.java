package com.healtheat.healtheat_api.chat;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="pending_actions")
class PendingChatAction {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="user_id",nullable=false) Long userId;
    @Column(name="action_type") String actionType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") ChatDtos.ActionPayload payload;
    String status;
    @Column(name="created_at",insertable=false,updatable=false) Instant createdAt;
    @Column(name="confirmed_at") Instant confirmedAt;
    protected PendingChatAction() {}
}
