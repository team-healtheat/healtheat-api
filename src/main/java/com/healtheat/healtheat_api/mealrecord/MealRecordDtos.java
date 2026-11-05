package com.healtheat.healtheat_api.mealrecord;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MealRecordDtos {
    private MealRecordDtos() {}
    public record FoodInput(@NotBlank @Size(max=200) String foodName, @Size(max=200) String servingDescription) {}
    public record DirectRequest(@NotNull LocalDate mealDate, @NotBlank @Size(max=30) String mealType,
                                @Size(max=30) String sourceType, @NotEmpty @Size(max=50) List<@NotNull @Valid FoodInput> items) {}
    public record RecommendationRequest(@NotNull @Positive Long recommendationId) {}
    public record UpdateRequest(@Size(max=30) String sourceType, @NotEmpty @Size(max=50) List<@NotNull @Valid FoodInput> items) {}
    public record Food(Long id, String foodName, String servingDescription) {}
    public record Record(Long id, LocalDate mealDate, String mealType, String sourceType,
                         boolean fromRecommendation, Long recommendationId, Instant createdAt, List<Food> items) {}
}
