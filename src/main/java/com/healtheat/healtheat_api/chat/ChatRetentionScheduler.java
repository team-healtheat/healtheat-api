package com.healtheat.healtheat_api.chat;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ChatRetentionScheduler {
    private final ChatService chat;
    ChatRetentionScheduler(ChatService chat) { this.chat=chat; }
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() { chat.deleteExpired(); }
    @Scheduled(fixedDelayString="${chat.retention.cleanup-interval-ms:60000}")
    void cleanup() { chat.deleteExpired(); }
}
