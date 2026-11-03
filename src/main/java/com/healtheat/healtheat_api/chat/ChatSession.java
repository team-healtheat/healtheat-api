package com.healtheat.healtheat_api.chat;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="chat_sessions")
class ChatSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="user_id",nullable=false) Long userId;
    @Column(name="created_at",insertable=false,updatable=false) Instant createdAt;
    @Column(name="updated_at",insertable=false) Instant updatedAt;
    protected ChatSession() {}
    ChatSession(long userId) { this.userId=userId; }
}
