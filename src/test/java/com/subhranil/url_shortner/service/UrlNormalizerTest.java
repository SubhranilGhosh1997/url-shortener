package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.exception.InvalidUrlException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // the same site written differently collapses to one form
            "https://github.com                 | https://github.com",
            "https://github.com/                | https://github.com",
            "HTTPS://GitHub.COM/                | https://github.com",
            "'  https://github.com  '           | https://github.com",
            "https://github.com:443/            | https://github.com",
            "http://example.com:80/a            | http://example.com/a",
            // things that can change the page are kept as-is
            "https://example.com:8443/          | https://example.com:8443",
            "https://example.com/Docs/          | https://example.com/Docs/",
            "https://example.com/docs           | https://example.com/docs",
            "https://example.com/?q=Spring+Boot | https://example.com?q=Spring+Boot",
            "https://example.com/a?b=2&a=1#Top  | https://example.com/a?b=2&a=1#Top",
            "https://example.com/a%20b          | https://example.com/a%20b",
            "http://user@example.com/x          | http://user@example.com/x",
    })
    void normalizes(String input, String expected) {
        assertEquals(expected, normalizer.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "www.google.com",          // no scheme: would become a broken relative redirect
            "ftp://example.com/file",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "https://",
            "https://exa mple.com",
    })
    void rejectsInvalidUrls(String input) {
        assertThrows(InvalidUrlException.class, () -> normalizer.normalize(input));
    }

    @Test
    void acceptsUrlOfExactlyMaxLength() {
        String url = urlOfLength(UrlNormalizer.MAX_LENGTH);
        assertEquals(url, normalizer.normalize(url));
    }

    @Test
    void rejectsUrlOneCharacterTooLong() {
        InvalidUrlException ex = assertThrows(InvalidUrlException.class,
                () -> normalizer.normalize(urlOfLength(UrlNormalizer.MAX_LENGTH + 1)));
        assertEquals("URL is too long: 2049 characters (maximum 2048)", ex.getMessage());
    }

    @Test
    void surroundingWhitespaceDoesNotCountTowardsTheLimit() {
        String url = urlOfLength(UrlNormalizer.MAX_LENGTH);
        assertEquals(url, normalizer.normalize("   " + url + "\n"));
    }

    @Test
    void rejectsHugeInputWithoutTryingToParseIt() {
        String huge = "https://example.com/" + "a".repeat(5_000_000) + " not a url";
        InvalidUrlException ex = assertThrows(InvalidUrlException.class, () -> normalizer.normalize(huge));
        assertTrue(ex.getMessage().startsWith("URL is too long"));
    }

    /** A valid, already-normalized URL of exactly {@code length} characters. */
    static String urlOfLength(int length) {
        String prefix = "https://example.com/";
        return prefix + "a".repeat(length - prefix.length());
    }
}
