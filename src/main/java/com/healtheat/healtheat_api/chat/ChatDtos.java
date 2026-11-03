package com.healtheat.healtheat_api.chat;

import com.healtheat.healtheat_api.health.HealthDtos;
import com.healtheat.healtheat_api.mealplan.MealPlanDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ChatDtos {
    private ChatDtos() {}
    public record MessageRequest(@Positive Long sessionId, @NotBlank @Size(max=4000) String message) {}
    public record Session(Long id, Instant createdAt, Instant updatedAt) {}
    public record Message(Long id, String role, String content, Instant createdAt) {}
    public record Change(Long factorId, String name, String type, String reportedStatus, String originalText, boolean active) {}
    public record ActionPayload(Long sessionId, String baselineHash, List<Change> changes) {}
    public record PendingAction(Long id, Long sessionId, String type, String status, List<Change> changes) {}
    public record Response(Long sessionId, String reply, String intent, PendingAction pendingAction, MealPlanDtos.Plan updatedMealPlan) {}
    public record ActionResult(Long id, String status, HealthDtos.ProfileResponse profile) {}
    public record FoodCount(String foodName, LocalDate from, LocalDate to, long count, boolean limitedToRetention) {}
}
