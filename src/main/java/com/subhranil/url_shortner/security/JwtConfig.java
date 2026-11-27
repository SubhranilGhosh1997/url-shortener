package com.subhranil.url_shortner.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RS256 keys for JWTs. Tokens are signed with the private key (only the auth module uses the encoder)
 * and verified with the public key. If the auth module moves to its own service, this service keeps
 * only the decoder and the public key.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@Slf4j
public class JwtConfig {

    @Bean
    KeyPair jwtKeyPair(JwtProperties props) throws GeneralSecurityException {
        boolean hasPrivate = props.privateKey() != null && !props.privateKey().isBlank();
        boolean hasPublic = props.publicKey() != null && !props.publicKey().isBlank();
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("Set both app.jwt.private-key and app.jwt.public-key, or neither");
        }
        if (!hasPrivate) {
            log.warn("No JWT keys configured (APP_JWT_PRIVATE_KEY / APP_JWT_PUBLIC_KEY): generated a temporary "
                    + "key pair. Fine for local development, but every restart invalidates all issued tokens.");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        return new KeyPair(
                rsa.generatePublic(new X509EncodedKeySpec(pemBody(props.publicKey(), "PUBLIC KEY"))),
                rsa.generatePrivate(new PKCS8EncodedKeySpec(pemBody(props.privateKey(), "PRIVATE KEY"))));
    }

    @Bean
    JwtEncoder jwtEncoder(KeyPair jwtKeyPair) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) jwtKeyPair.getPublic())
                .privateKey((RSAPrivateKey) jwtKeyPair.getPrivate())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    JwtDecoder jwtDecoder(KeyPair jwtKeyPair, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) jwtKeyPair.getPublic()).build();
        // Checks signature, expiry ("exp"/"nbf" with a small clock skew) and that "iss" is ours.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    private static byte[] pemBody(String pem, String type) {
        String body = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replace("\\n", "")        // tolerate "\n" escapes from single-line env vars
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.jwt key is not a valid PEM '" + type + "'", e);
        }
    }
}
