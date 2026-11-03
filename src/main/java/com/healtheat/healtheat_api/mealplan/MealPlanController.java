package com.healtheat.healtheat_api.mealplan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/meal-plans")
@SecurityRequirement(name = "accessCookie")
class MealPlanController {
    private final MealPlanQueryService plans;
    private final MealPlanGenerationService generation;
    private final MealRegenerationService regeneration;
    public record RegenerateRequest(@Size(max = 1000) String additionalRequest) {}
    MealPlanController(MealPlanQueryService plans, MealPlanGenerationService generation, MealRegenerationService regeneration) { this.plans = plans; this.generation = generation; this.regeneration = regeneration; }
    @PostMapping("/generate")
    @Operation(summary = "오늘 식단 생성", description = "이미 생성된 식단이 있으면 그대로 반환합니다. CSRF 토큰이 필요합니다.")
    MealPlanDtos.Plan generate(@AuthenticationPrincipal Jwt jwt) {
        return generation.generate(Long.parseLong(jwt.getSubject()), LocalDate.now());
    }
    @PostMapping("/{planId}/meals/{mealType}/regenerate")
    @Operation(summary = "한 끼 재추천", description = "이전 추천을 보존하고 요청한 끼니만 교체합니다. CSRF 토큰이 필요합니다.")
    MealPlanDtos.Plan regenerate(@AuthenticationPrincipal Jwt jwt, @PathVariable long planId, @PathVariable String mealType,
                                 @Valid @RequestBody(required = false) RegenerateRequest request) {
        return regeneration.regenerate(Long.parseLong(jwt.getSubject()), planId, mealType, request == null ? null : request.additionalRequest());
    }
    @GetMapping("/today")
    @Operation(summary = "오늘 식단 조회", description = "서버 날짜 기준으로 저장된 식단만 조회하며 없으면 204를 반환합니다. AI 호출이나 식단 생성은 하지 않습니다.")
    ResponseEntity<MealPlanDtos.Plan> today(@AuthenticationPrincipal Jwt jwt) {
        var plan = plans.get(Long.parseLong(jwt.getSubject()), LocalDate.now());
        return plan == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(plan);
    }
}
