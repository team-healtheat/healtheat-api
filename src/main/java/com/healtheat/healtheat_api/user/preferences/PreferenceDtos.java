package com.healtheat.healtheat_api.user.preferences;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class PreferenceDtos {
    private PreferenceDtos() {}
    public enum AllergyStatus { UNANSWERED, UNKNOWN, NONE, PRESENT }
    public record UpdateRequest(Boolean allowDiningOut,
        @Size(max = 50) List<@NotNull @NotBlank @Size(max = 200) String> likedFoods,
        @Size(max = 50) List<@NotNull @NotBlank @Size(max = 200) String> dislikedFoods,
        @Size(max = 50) List<@NotNull @NotBlank @Size(max = 200) String> dietaryPreferences,
        AllergyStatus allergyStatus,
        @Size(max = 50) List<@NotNull @NotBlank @Size(max = 200) String> allergens) {}
    public record Response(Boolean allowDiningOut, List<String> likedFoods, List<String> dislikedFoods,
        List<String> dietaryPreferences, AllergyStatus allergyStatus, List<String> allergens) {}
}
