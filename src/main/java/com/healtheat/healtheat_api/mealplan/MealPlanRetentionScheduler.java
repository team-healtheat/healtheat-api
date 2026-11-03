package com.healtheat.healtheat_api.mealplan;

import com.healtheat.healtheat_api.mealrecord.MealRecordPolicy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class MealPlanRetentionScheduler {
    private final MealPlanRetentionService retention;
    MealPlanRetentionScheduler(MealPlanRetentionService retention) { this.retention = retention; }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { retention.deleteExpired(MealRecordPolicy.today()); }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    public void daily() { retention.deleteExpired(MealRecordPolicy.today()); }
}
