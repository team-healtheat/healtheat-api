package com.healtheat.healtheat_api.auth;

import com.healtheat.healtheat_api.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "인증", description = "CSRF 토큰을 먼저 조회하고 응답의 headerName과 token을 POST 요청에 포함합니다.")
public class AuthController {
    private final AuthService auth;
    private final JwtEncoder encoder;
    private final String sameSite;
    public AuthController(AuthService auth, JwtEncoder encoder, @Value("${app.security.cookie-same-site}") String sameSite) {
        this.auth = auth; this.encoder = encoder; this.sameSite = sameSite;
    }
    @GetMapping("/api/auth/csrf")
    @Operation(summary = "CSRF 토큰 조회", description = "쿠키와 함께 응답 토큰을 X-XSRF-TOKEN 헤더에 전달합니다. 로그인 후 토큰을 다시 조회하세요.")
    public AuthDtos.CsrfResponse csrf(CsrfToken csrf) { return new AuthDtos.CsrfResponse(csrf.getHeaderName(), csrf.getToken()); }
    @PostMapping("/api/auth/signup") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회원가입", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "GET /api/auth/csrf 응답의 token"))
    public AuthDtos.UserResponse signup(@Valid @RequestBody AuthDtos.SignupRequest request) { return response(auth.signup(request)); }
    @PostMapping("/api/auth/login")
    @Operation(summary = "로그인", description = "성공 시 30분 유효한 HttpOnly Secure access_token 쿠키를 발급합니다.", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "GET /api/auth/csrf 응답의 token"))
    public AuthDtos.UserResponse login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletResponse response) {
        User user = auth.authenticate(request);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(user.getId().toString()).issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(30))).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("access_token", token).httpOnly(true).secure(true)
            .sameSite(sameSite).path("/").maxAge(Duration.ofMinutes(30)).build().toString());
        return response(user);
    }
    @GetMapping("/api/users/me")
    @Operation(summary = "내 정보 조회", security = @SecurityRequirement(name = "accessCookie"))
    public AuthDtos.UserResponse me(@AuthenticationPrincipal Jwt jwt) { return response(auth.getUser(Long.parseLong(jwt.getSubject()))); }
    private static AuthDtos.UserResponse response(User user) { return new AuthDtos.UserResponse(user.getId(), user.getEmail(), user.getRole()); }
}

