package com.healtheat.healtheat_api.mealrecord;

import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar-day policy: today counts as the first day, in Korea time. */
public final class MealRecordPolicy {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private MealRecordPolicy() {}
    public static LocalDate today() { return LocalDate.now(ZONE); }
    public static LocalDate retentionStart(LocalDate today) { return today.minusDays(29); }
    public static LocalDate recommendationStart(LocalDate today) { return today.minusDays(13); }
}
