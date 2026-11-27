package com.subhranil.url_shortner.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the auth API. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320)
            @Schema(example = "alice@example.com") String email,
            // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently cut.
            @NotBlank @Size(min = 8, max = 72)
            @Schema(example = "correct-horse-battery", description = "8 to 72 characters") String password) {
    }

    public record LoginRequest(
            @NotBlank @Schema(example = "alice@example.com") String email,
            @NotBlank @Schema(example = "correct-horse-battery") String password) {
    }

    public record AuthResponse(
            @Schema(description = "Send as 'Authorization: Bearer <accessToken>'") String accessToken,
            String tokenType,
            @Schema(description = "Seconds until the token expires") long expiresIn,
            long userId,
            String email) {
    }

    public record MeResponse(long userId, String email) {
    }
}
