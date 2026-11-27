package com.subhranil.url_shortner.service;

import org.springframework.stereotype.Component;

/**
 * Turns a database id into a random-looking, fixed-length Base62 short code (e.g. 1 -> "q8ZfK2w").
 *
 * <p>The id is scrambled with a 4-round Feistel permutation over 42 bits, then "cycle-walked" (re-applied
 * until the value is below 62^7) so the result always fits in 7 Base62 characters. Both steps are
 * one-to-one, so every id below 62^7 (~3.5 trillion) gets a different code: codes stay unique by
 * construction and no collision check against the database is needed.
 * This hides the sequence; it is not encryption.
 */
@Component
public class ShortCodeGenerator {

    static final int CODE_LENGTH = 7;

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** 62^7: the number of distinct 7-character Base62 codes. */
    static final long MAX_IDS = 3_521_614_606_208L;

    private static final int HALF_BITS = 21;              // 2^42 > 62^7
    private static final long HALF_MASK = (1L << HALF_BITS) - 1;

    // Changing these keys changes every future code and could make a new code clash with an existing one.
    private static final long[] ROUND_KEYS = {0x5A17C3L, 0x1F0B9DL, 0x6E3D21L, 0x3C88F5L};

    public String generate(long id) {
        if (id < 1 || id >= MAX_IDS) {
            throw new IllegalArgumentException("id out of range for a " + CODE_LENGTH + "-char code: " + id);
        }
        long value = id;
        do {
            value = permute(value);
        } while (value >= MAX_IDS);

        char[] code = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            code[i] = ALPHABET.charAt((int) (value % 62));
            value /= 62;
        }
        return new String(code);
    }

    /** A Feistel network: a bijection on [0, 2^42) whatever the round function is. */
    private static long permute(long value) {
        long left = value >>> HALF_BITS;
        long right = value & HALF_MASK;
        for (long key : ROUND_KEYS) {
            long next = left ^ round(right, key);
            left = right;
            right = next;
        }
        return (left << HALF_BITS) | right;
    }

    private static long round(long half, long key) {
        long h = (half ^ key) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h & HALF_MASK;
    }
}
