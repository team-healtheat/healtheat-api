package com.healtheat.healtheat_api.user.preferences;

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
@RequestMapping("/api/users/me/preferences")
@Tag(name = "사용자 선호 및 알레르기")
@SecurityRequirement(name = "accessCookie")
public class PreferenceController {
    private final PreferenceService preferences;
    public PreferenceController(PreferenceService preferences) { this.preferences = preferences; }
    @GetMapping
    @Operation(summary = "내 선호 및 알레르기 조회")
    public PreferenceDtos.Response get(@AuthenticationPrincipal Jwt jwt) { return preferences.get(Long.parseLong(jwt.getSubject())); }
    @PatchMapping
    @Operation(summary = "내 선호 및 알레르기 변경", description = "제공된 항목만 변경합니다. 알레르기는 없음(NONE) 또는 항목 있음(PRESENT)이며 상태와 목록을 함께 보냅니다. UNANSWERED는 선택 전 내부 상태입니다.",
        parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true))
    public PreferenceDtos.Response update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreferenceDtos.UpdateRequest request) {
        return preferences.update(Long.parseLong(jwt.getSubject()), request);
    }
}
