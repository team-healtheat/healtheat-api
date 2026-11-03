package com.healtheat.healtheat_api.mealrecord;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class MealRecordRetentionScheduler {
    private final MealRecordRetentionService retention;
    MealRecordRetentionScheduler(MealRecordRetentionService retention) { this.retention=retention; }
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { retention.deleteExpired(MealRecordPolicy.today()); }
    @Scheduled(cron="0 0 0 * * *",zone="Asia/Seoul")
    public void daily() { retention.deleteExpired(MealRecordPolicy.today()); }
}
