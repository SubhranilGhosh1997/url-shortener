package com.subhranil.url_shortner.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the signed-in user from a verified JWT. This is the only thing the URL code knows about users:
 * an id taken from the token's "sub" claim. It never touches the auth module or the users table.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
