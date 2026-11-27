package com.subhranil.url_shortner.security;

import com.subhranil.url_shortner.auth.AppUser;
import com.subhranil.url_shortner.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Signing with the private key and verifying with the public key, without Spring. */
class JwtRoundTripTest {

    private final JwtConfig config = new JwtConfig();

    @Test
    void issuedTokenVerifiesAndCarriesUserId() throws Exception {
        JwtProperties props = new JwtProperties(null, null, Duration.ofHours(1), "url-shortener");
        KeyPair keys = config.jwtKeyPair(props);
        TokenService tokens = new TokenService(config.jwtEncoder(keys), props);

        Jwt jwt = config.jwtDecoder(keys, props).decode(tokens.issue(user(42L, "a@example.com")));

        assertEquals("42", jwt.getSubject());
        assertEquals(42L, CurrentUser.id(jwt));
        assertEquals("a@example.com", jwt.getClaimAsString("email"));
        assertEquals("url-shortener", jwt.getClaimAsString("iss"));
        assertEquals(3600, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
    }

    @Test
    void keysLoadFromPemStrings() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair original = gen.generateKeyPair();
        JwtProperties props = new JwtProperties(
                pem("PRIVATE KEY", original.getPrivate().getEncoded()),
                pem("PUBLIC KEY", original.getPublic().getEncoded()),
                null, null);

        KeyPair loaded = config.jwtKeyPair(props);

        assertArrayEquals(original.getPublic().getEncoded(), loaded.getPublic().getEncoded());
        assertArrayEquals(original.getPrivate().getEncoded(), loaded.getPrivate().getEncoded());
    }

    @Test
    void onlyOneKeyConfiguredFailsFast() {
        JwtProperties props = new JwtProperties("-----BEGIN PRIVATE KEY-----abc", null, null, null);
        assertThrows(IllegalStateException.class, () -> config.jwtKeyPair(props));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        JwtProperties props = new JwtProperties(null, null, null, null);
        KeyPair ours = config.jwtKeyPair(props);
        KeyPair attacker = config.jwtKeyPair(props);
        String forged = new TokenService(config.jwtEncoder(attacker), props).issue(user(1L, "x@example.com"));

        JwtDecoder decoder = config.jwtDecoder(ours, props);
        assertThrows(JwtException.class, () -> decoder.decode(forged));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JwtProperties props = new JwtProperties(null, null, null, null);
        KeyPair keys = config.jwtKeyPair(props);
        // Issued 10 min ago, expired 5 min ago: well beyond the validator's 60 s clock-skew allowance.
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer()).subject("1")
                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300))
                .build();
        String token = config.jwtEncoder(keys).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        JwtDecoder decoder = config.jwtDecoder(keys, props);
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        JwtProperties ours = new JwtProperties(null, null, null, "url-shortener");
        JwtProperties other = new JwtProperties(null, null, null, "someone-else");
        KeyPair keys = config.jwtKeyPair(ours);
        String token = new TokenService(config.jwtEncoder(keys), other).issue(user(1L, "x@example.com"));

        JwtDecoder decoder = config.jwtDecoder(keys, ours);
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    private static AppUser user(long id, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
