package com.healtheat.healtheat_api.health;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class HealthDtos {
    private HealthDtos() {}
    public record AnalyzeRequest(@NotBlank @Size(max = 4000) String rawText) {}
    // Model output is a candidate only; support and master IDs come from the server.
    public record AnalysisFactor(@NotBlank @Size(max = 200) String name,
                                 @NotBlank @Size(max = 80) String type,
                                 @NotBlank @Size(max = 80) String reportedStatus,
                                 @Size(max = 1000) String originalText) {}
    public record HealthFactorAnalysisResponse(@NotNull @Size(max = 50) List<@NotNull @Valid AnalysisFactor> factors) {}
    public record Candidate(Long factorId, String name, String type, String reportedStatus,
                            String originalText, String supported) {}
    public record AnalysisResponse(List<Candidate> factors) {}
    public record ConfirmedFactor(@Positive Long factorId,
                                  @NotBlank @Size(max = 200) String name,
                                  @NotBlank @Size(max = 80) String type,
                                  @NotBlank @Size(max = 80) String reportedStatus,
                                  @Size(max = 1000) String originalText,
                                  @NotNull Boolean active) {}
    public record ConfirmRequest(@NotNull @Size(max = 50) List<@NotNull @Valid ConfirmedFactor> factors) {}
    public record ProfileFactor(Long id, Long factorId, String name, String type, String reportedStatus,
                                String originalText, boolean active, String supported) {}
    public record ProfileResponse(List<ProfileFactor> factors) {}
}
