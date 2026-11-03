package com.healtheat.healtheat_api.chat;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="chat_messages")
class ChatMessage {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="chat_session_id",nullable=false) Long sessionId;
    String role;
    @Column(columnDefinition="text") String content;
    @Column(name="created_at",insertable=false,updatable=false) Instant createdAt;
    protected ChatMessage() {}
    ChatMessage(long sessionId,String role,String content) { this.sessionId=sessionId; this.role=role; this.content=content; }
}
