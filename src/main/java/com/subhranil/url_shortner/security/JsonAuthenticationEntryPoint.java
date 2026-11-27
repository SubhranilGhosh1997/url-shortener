package com.subhranil.url_shortner.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/** Returns 401 in the same JSON shape as every other API error ({status, message, timestamp}). */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        boolean sentToken = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        String message = sentToken
                ? "Invalid or expired token"
                : "Authentication required: send 'Authorization: Bearer <token>' (get one from /api/auth/login)";

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, sentToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Fixed messages only (no user input), so plain string building is safe here.
        response.getWriter().write("{\"status\":401,\"message\":\"" + message.replace("\"", "\\\"")
                + "\",\"timestamp\":\"" + Instant.now() + "\"}");
    }
}
