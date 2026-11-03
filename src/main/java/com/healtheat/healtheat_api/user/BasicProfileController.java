package com.healtheat.healtheat_api.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users/me/profile")
@SecurityRequirement(name = "accessCookie")
public class BasicProfileController {
    private final UserRepository users;
    public BasicProfileController(UserRepository users) { this.users = users; }

    public record ProfileRequest(@PositiveOrZero Integer age, @Size(max = 100) String gender) {}
    public record ProfileResponse(Integer age, String gender) {}

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "기본 정보 조회")
    public ProfileResponse get(@AuthenticationPrincipal Jwt jwt) { return response(user(jwt)); }

    @PutMapping
    @Transactional
    @Operation(summary = "기본 정보 저장", description = "나이와 성별을 전체 교체합니다. null 또는 생략은 해당 값을 삭제합니다.", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true))
    public ProfileResponse put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest input) {
        User user = user(jwt);
        String gender = input.gender() == null ? null : input.gender().trim();
        if (gender != null && gender.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gender must not be blank");
        user.updateBasicProfile(input.age(), gender);
        return response(user);
    }
    private User user(Jwt jwt) {
        return users.findById(Long.parseLong(jwt.getSubject()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }
    private ProfileResponse response(User user) { return new ProfileResponse(user.getAge(), user.getGender()); }
}
