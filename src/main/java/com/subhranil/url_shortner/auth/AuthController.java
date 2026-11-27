package com.subhranil.url_shortner.auth;

import com.subhranil.url_shortner.auth.AuthDtos.AuthResponse;
import com.subhranil.url_shortner.auth.AuthDtos.LoginRequest;
import com.subhranil.url_shortner.auth.AuthDtos.MeResponse;
import com.subhranil.url_shortner.auth.AuthDtos.RegisterRequest;
import com.subhranil.url_shortner.exception.ErrorResponse;
import com.subhranil.url_shortner.exception.ResourceNotFoundException;
import com.subhranil.url_shortner.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Sign up and log in. Copy accessToken into the Authorize button above.")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Create an account", description = "Returns an access token, so you are logged in right away.")
    @SecurityRequirements
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Invalid email or password too short/long",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req.email(), req.password()));
    }

    @Operation(summary = "Log in")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "401", description = "Wrong email or password",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req.email(), req.password()));
    }

    @Operation(summary = "Who am I?", description = "The account the current token belongs to.")
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        AppUser user = authService.findById(CurrentUser.id(jwt))
                .orElseThrow(() -> new ResourceNotFoundException("Account no longer exists"));
        return ResponseEntity.ok(new MeResponse(user.getId(), user.getEmail()));
    }
}
