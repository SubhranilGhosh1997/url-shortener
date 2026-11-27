package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.exception.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Puts a URL into one canonical form so the same link is stored and matched only once.
 *
 * <p>Only changes that can never alter which page is served are made:
 * <ul>
 *   <li>surrounding whitespace is trimmed</li>
 *   <li>scheme and host are lowercased ({@code HTTPS://GitHub.com} -> {@code https://github.com})</li>
 *   <li>default ports are dropped ({@code :80} for http, {@code :443} for https)</li>
 *   <li>a bare root path is dropped ({@code https://github.com/} -> {@code https://github.com})</li>
 * </ul>
 * Paths, query strings and fragments are kept exactly as given: {@code /docs/} and {@code /docs} can be
 * different pages on some servers, and query parameter order can matter.
 *
 * <p>Only absolute http/https URLs are accepted. Anything else would produce a broken redirect.
 */
@Component
public class UrlNormalizer {

    /**
     * Longest URL we store. Must match url_mapping.original_url VARCHAR(2048) (V1 migration); a longer value
     * would fail in the database with a 500. 2048 is also the practical limit of most browsers and servers.
     */
    public static final int MAX_LENGTH = 2048;

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("URL must not be blank");
        }
        // Checked before parsing, so huge inputs are rejected cheaply. Normalizing never makes a URL longer.
        if (raw.trim().length() > MAX_LENGTH) {
            throw new InvalidUrlException(tooLongMessage(raw.trim().length()));
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Not a valid URL: " + raw.trim());
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new InvalidUrlException("URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("URL must include a host name, e.g. https://example.com");
        }

        StringBuilder url = new StringBuilder(scheme).append("://");
        if (uri.getRawUserInfo() != null) {
            url.append(uri.getRawUserInfo()).append('@');
        }
        url.append(uri.getHost().toLowerCase(Locale.ROOT));

        int port = uri.getPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        if (port != -1 && !defaultPort) {
            url.append(':').append(port);
        }

        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            url.append(path);
        }
        if (uri.getRawQuery() != null) {
            url.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            url.append('#').append(uri.getRawFragment());
        }
        return url.toString();
    }

    static String tooLongMessage(int length) {
        return "URL is too long: " + length + " characters (maximum " + MAX_LENGTH + ")";
    }
}
