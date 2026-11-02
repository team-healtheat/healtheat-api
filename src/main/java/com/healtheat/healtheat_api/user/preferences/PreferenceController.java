package com.healtheat.healtheat_api.user.preferences;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/preferences")
@Tag(name = "사용자 선호 및 알레르기")
@SecurityRequirement(name = "accessCookie")
public class PreferenceController {
    public record AllergyGroup(String name, List<String> items) {}
    private static final List<AllergyGroup> ALLERGY_OPTIONS = List.of(
        new AllergyGroup("곡류·콩류", List.of("밀", "메밀", "대두", "땅콩")),
        new AllergyGroup("우유·난류", List.of("우유", "달걀")),
        new AllergyGroup("육류", List.of("돼지고기", "쇠고기", "닭고기")),
        new AllergyGroup("생선·해산물", List.of("고등어", "게", "새우", "오징어", "조개류", "전복", "홍합", "굴")),
        new AllergyGroup("과일·채소", List.of("복숭아", "토마토")),
        new AllergyGroup("기타 표시 대상", List.of("호두", "잣", "아황산류"))
    );
    private final PreferenceService preferences;
    public PreferenceController(PreferenceService preferences) { this.preferences = preferences; }
    @GetMapping
    @Operation(summary = "내 선호 및 알레르기 조회")
    public PreferenceDtos.Response get(@AuthenticationPrincipal Jwt jwt) { return preferences.get(Long.parseLong(jwt.getSubject())); }
    @GetMapping("/allergy-options")
    @Operation(summary = "온보딩 알레르기 항목 목록", description = "복수 선택 항목입니다. 없음과 기타 직접 입력은 별도로 처리합니다.")
    public List<AllergyGroup> allergyOptions() { return ALLERGY_OPTIONS; }
    @PatchMapping
    @Operation(summary = "내 선호 및 알레르기 변경", description = "제공된 항목만 변경합니다. 알레르기는 없음(NONE) 또는 항목 있음(PRESENT)이며 상태와 목록을 함께 보냅니다. UNANSWERED는 선택 전 내부 상태입니다.",
        parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true))
    public PreferenceDtos.Response update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreferenceDtos.UpdateRequest request) {
        return preferences.update(Long.parseLong(jwt.getSubject()), request);
    }
}
