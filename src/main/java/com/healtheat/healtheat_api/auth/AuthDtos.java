package com.healtheat.healtheat_api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}
    public record SignupRequest(@NotBlank @Email @Size(max = 254) String email,
                                @NotBlank @Size(min = 8, max = 72) String password,
                                @NotBlank @Size(max = 72) String passwordConfirm) {}
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 72) String password) {}
    public record UserResponse(Long id, String email, String role) {}
    public record CsrfResponse(String headerName, String token) {}
    public record ErrorResponse(int status, String code, String message) {}
}
