package com.subhranil.url_shortner.auth;

import com.subhranil.url_shortner.auth.AuthDtos.AuthResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /**
     * Hash checked when the email is unknown, so "no such user" takes as long as "wrong password"
     * and response times don't reveal which emails are registered.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    public AuthResponse register(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(LocalDateTime.now());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at the same moment: the unique constraint decides.
            throw new EmailAlreadyRegisteredException(email);
        }
        return toResponse(user);
    }

    public AuthResponse login(String rawEmail, String password) {
        Optional<AppUser> user = users.findByEmail(normalizeEmail(rawEmail));
        String hash = user.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);
        if (user.isEmpty() || !matches) {
            // Same error for unknown email and wrong password: don't reveal which one it was.
            throw new InvalidCredentialsException();
        }
        return toResponse(user.get());
    }

    public Optional<AppUser> findById(long id) {
        return users.findById(id);
    }

    private AuthResponse toResponse(AppUser user) {
        return new AuthResponse(tokenService.issue(user), "Bearer", tokenService.ttlSeconds(),
                user.getId(), user.getEmail());
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
