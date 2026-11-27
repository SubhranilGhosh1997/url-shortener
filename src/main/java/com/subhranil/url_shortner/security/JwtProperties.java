package com.subhranil.url_shortner.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT settings ({@code app.jwt.*}).
 *
 * @param privateKey PEM (PKCS#8, "BEGIN PRIVATE KEY") used to sign tokens. Pass it via the
 *                   APP_JWT_PRIVATE_KEY environment variable; never commit it.
 * @param publicKey  PEM (X.509, "BEGIN PUBLIC KEY") used to verify tokens (APP_JWT_PUBLIC_KEY).
 *                   If both keys are empty, a temporary key pair is generated at startup (dev only:
 *                   every restart invalidates all tokens).
 * @param ttl        how long an access token is valid
 * @param issuer     "iss" claim written into and required on every token
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String privateKey, String publicKey, Duration ttl, String issuer) {

    public JwtProperties {
        if (ttl == null) ttl = Duration.ofHours(1);
        if (issuer == null || issuer.isBlank()) issuer = "url-shortener";
    }
}
