package com.healtheat.healtheat_api.auth;

import com.healtheat.healtheat_api.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
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
    private final RefreshSessionService refreshSessions;
    private final AccountDeletionService accountDeletion;
    private final String sameSite;
    public AuthController(AuthService auth, JwtEncoder encoder, RefreshSessionService refreshSessions, AccountDeletionService accountDeletion, @Value("${app.security.cookie-same-site}") String sameSite) {
        this.auth = auth; this.encoder = encoder; this.refreshSessions=refreshSessions; this.accountDeletion=accountDeletion; this.sameSite = sameSite;
    }
    @GetMapping("/api/auth/csrf")
    @Operation(summary = "CSRF 토큰 조회", description = "쿠키와 함께 응답 토큰을 X-XSRF-TOKEN 헤더에 전달합니다. 로그인 후 토큰을 다시 조회하세요.")
    public AuthDtos.CsrfResponse csrf(CsrfToken csrf) { return new AuthDtos.CsrfResponse(csrf.getHeaderName(), csrf.getToken()); }
    @PostMapping("/api/auth/signup") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회원가입", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "GET /api/auth/csrf 응답의 token"))
    public AuthDtos.UserResponse signup(@Valid @RequestBody AuthDtos.SignupRequest request) { return response(auth.signup(request)); }
    @PostMapping("/api/auth/login")
    @Operation(summary = "로그인", description = "성공 시 30분 유효한 HttpOnly Secure access_token 쿠키를 발급합니다.", parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "GET /api/auth/csrf 응답의 token"))
    public AuthDtos.UserResponse login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        User user = auth.authenticate(request);
        refreshSessions.revoke(cookie(httpRequest,"refresh_token"));
        issueAccessCookie(user.getId(),response);
        response.addHeader(HttpHeaders.SET_COOKIE,refreshCookie(refreshSessions.issue(user.getId()),Duration.ofDays(30)).toString());
        return response(user);
    }
    private void issueAccessCookie(long userId,HttpServletResponse response) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(Long.toString(userId)).issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(30))).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie(token, Duration.ofMinutes(30)).toString());
    }
    @PostMapping("/api/auth/refresh")
    @Operation(summary="로그인 갱신",description="CSRF 보호를 적용하며 갱신 토큰을 교체합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    public AuthDtos.UserResponse refresh(HttpServletRequest request,HttpServletResponse response) {
        var rotated=refreshSessions.rotate(cookie(request,"refresh_token"));
        issueAccessCookie(rotated.userId(),response);
        response.addHeader(HttpHeaders.SET_COOKIE,refreshCookie(rotated.token(),Duration.ofDays(30)).toString());
        return response(auth.getUser(rotated.userId()));
    }
    @PostMapping("/api/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "로그아웃", description = "쿠키를 만료합니다. 이미 발급된 JWT는 만료될 때까지 유효할 수 있습니다.", security = @SecurityRequirement(name = "accessCookie"), parameters = @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "GET /api/auth/csrf 응답의 token"))
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        refreshSessions.revoke(cookie(request,"refresh_token"));
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie("", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
    }
    private String cookie(HttpServletRequest request,String name) {
        if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if(name.equals(c.getName())) return c.getValue();
        return null;
    }
    private ResponseCookie refreshCookie(String value,Duration age) { return ResponseCookie.from("refresh_token",value).httpOnly(true).secure(true).sameSite(sameSite).path("/api/auth").maxAge(age).build(); }
    private ResponseCookie accessCookie(String value, Duration maxAge) {
        // No Domain attribute: issue and expire the same host-only cookie.
        return ResponseCookie.from("access_token", value).httpOnly(true).secure(true)
            .sameSite(sameSite).path("/").maxAge(maxAge).build();
    }
    @GetMapping("/api/users/me")
    @Operation(summary = "내 정보 조회", security = @SecurityRequirement(name = "accessCookie"))
    public AuthDtos.UserResponse me(@AuthenticationPrincipal Jwt jwt) { return response(auth.getUser(Long.parseLong(jwt.getSubject()))); }
    @PatchMapping("/api/users/me/nickname")
    @Operation(summary="닉네임 변경",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    public AuthDtos.UserResponse nickname(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AuthDtos.NicknameRequest body) {
        return response(auth.updateNickname(Long.parseLong(jwt.getSubject()),body.nickname()));
    }
    @DeleteMapping("/api/users/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="회원 탈퇴",description="비밀번호 확인 후 계정과 개인 데이터를 삭제합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    public void delete(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AuthDtos.DeleteAccountRequest body,HttpServletResponse response) {
        accountDeletion.delete(Long.parseLong(jwt.getSubject()),body.password());
        response.addHeader(HttpHeaders.SET_COOKIE,accessCookie("",Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,refreshCookie("",Duration.ZERO).toString());
    }
    private static AuthDtos.UserResponse response(User user) { return new AuthDtos.UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getNickname()); }
}
