package com.subhranil.url_shortner.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository users;
    private PasswordEncoder encoder;
    private TokenService tokens;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = spy(new BCryptPasswordEncoder(4)); // low cost: fast tests
        tokens = mock(TokenService.class);
        when(tokens.issue(any())).thenReturn("token");
        when(tokens.ttlSeconds()).thenReturn(3600L);
        when(users.saveAndFlush(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });
        auth = new AuthService(users, encoder, tokens);
    }

    @Test
    void registerNormalizesEmailAndStoresOnlyAHash() {
        var res = auth.register("  Alice@Example.COM ", "correct-horse");

        assertEquals("alice@example.com", res.email());
        assertEquals(7L, res.userId());
        assertEquals("token", res.accessToken());
        verify(users).saveAndFlush(argThat(u ->
                u.getEmail().equals("alice@example.com")
                        && !u.getPasswordHash().contains("correct-horse")
                        && encoder.matches("correct-horse", u.getPasswordHash())));
    }

    @Test
    void registerRejectsExistingEmail() {
        when(users.existsByEmail("alice@example.com")).thenReturn(true);
        assertThrows(EmailAlreadyRegisteredException.class, () -> auth.register("ALICE@example.com", "password1"));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void registerRaceOnSameEmailIsAConflictNotA500() {
        doThrow(new DataIntegrityViolationException("uk_users_email")).when(users).saveAndFlush(any());
        assertThrows(EmailAlreadyRegisteredException.class, () -> auth.register("a@example.com", "password1"));
    }

    @Test
    void loginWithCorrectPassword() {
        AppUser u = user("alice@example.com", encoder.encode("correct-horse"));
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));

        assertEquals("token", auth.login(" ALICE@example.com", "correct-horse").accessToken());
    }

    @Test
    void loginWithWrongPasswordFails() {
        AppUser u = user("alice@example.com", encoder.encode("correct-horse"));
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));

        assertThrows(InvalidCredentialsException.class, () -> auth.login("alice@example.com", "wrong"));
    }

    @Test
    void loginWithUnknownEmailFailsTheSameWayAndStillChecksAHash() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        clearInvocations(encoder);

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> auth.login("nobody@example.com", "whatever"));

        assertEquals("Invalid email or password", ex.getMessage());
        verify(encoder).matches(eq("whatever"), anyString()); // same work as a real check: no timing leak
    }

    private static AppUser user(String email, String hash) {
        AppUser u = new AppUser();
        u.setId(7L);
        u.setEmail(email);
        u.setPasswordHash(hash);
        return u;
    }
}
