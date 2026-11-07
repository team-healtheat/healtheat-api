package com.healtheat.healtheat_api.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/health-profiles")
@Tag(name = "건강 프로필")
@SecurityRequirement(name = "accessCookie")
public class HealthProfileController {
    private final HealthProfileService profiles;
    public HealthProfileController(HealthProfileService profiles) { this.profiles = profiles; }
    @PostMapping("/analyze")
    @Operation(summary = "건강 정보 분석", description = "사용자 확인 전 후보만 반환하며 DB에 저장하지 않습니다.", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true))
    public HealthDtos.AnalysisResponse analyze(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HealthDtos.AnalyzeRequest request) {
        return profiles.analyze(Long.parseLong(jwt.getSubject()), request.rawText());
    }
    @PutMapping
    @Operation(summary = "확인된 건강 프로필 저장", description = "확인된 전체 목록으로 갱신합니다. 목록에 없는 기존 항목은 비활성화합니다.", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true))
    public HealthDtos.ProfileResponse confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HealthDtos.ConfirmRequest request) {
        return profiles.confirm(Long.parseLong(jwt.getSubject()), request);
    }
    @GetMapping
    @Operation(summary = "내 건강 프로필 조회", description = "활성·비활성 항목을 함께 반환합니다.")
    public HealthDtos.ProfileResponse get(@AuthenticationPrincipal Jwt jwt) {
        return profiles.get(Long.parseLong(jwt.getSubject()));
    }
    @GetMapping("/options")
    @Operation(summary = "선택 가능한 건강요인 목록")
    public java.util.List<String> options() { return profiles.options(); }
    @GetMapping("/selection")
    @Operation(summary = "건강요인 선택 상태 조회")
    public HealthDtos.SelectionState selection(@AuthenticationPrincipal Jwt jwt) { return profiles.selection(Long.parseLong(jwt.getSubject())); }
    @PutMapping("/selection")
    @Operation(summary = "선택한 질환 저장", description = "AI 분석 없이 선택한 질환과 직접 입력을 저장합니다. 없음은 다른 항목과 함께 선택할 수 없습니다.", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true))
    public HealthDtos.ProfileResponse select(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HealthDtos.SelectionRequest request) {
        return profiles.select(Long.parseLong(jwt.getSubject()), request);
    }
}
