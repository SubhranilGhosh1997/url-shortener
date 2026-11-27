package com.subhranil.url_shortner.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void codesAreSevenBase62Characters() {
        for (long id : new long[]{1, 2, 62, 1_000_000, ShortCodeGenerator.MAX_IDS - 1}) {
            String code = generator.generate(id);
            assertEquals(ShortCodeGenerator.CODE_LENGTH, code.length(), "id " + id);
            assertTrue(code.matches("[0-9A-Za-z]{7}"), "id " + id + " -> " + code);
        }
    }

    @Test
    void codesAreUniqueAcrossFirstMillionIds() {
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 1_000_000; id++) {
            assertTrue(seen.add(generator.generate(id)), "duplicate code for id " + id);
        }
    }

    @Test
    void sameIdAlwaysGivesSameCode() {
        assertEquals(generator.generate(42), generator.generate(42));
    }

    @Test
    void consecutiveIdsDoNotLookSequential() {
        // For truly random codes, consecutive ones differ in ~6.9 of 7 positions on average.
        int totalDiffering = 0;
        int pairs = 1000;
        for (long id = 1; id <= pairs; id++) {
            String a = generator.generate(id);
            String b = generator.generate(id + 1);
            for (int i = 0; i < a.length(); i++) {
                if (a.charAt(i) != b.charAt(i)) totalDiffering++;
            }
        }
        double average = (double) totalDiffering / pairs;
        assertTrue(average > 6.5, "consecutive codes share too many characters, avg differing = " + average);

        // No single position may be constant or step predictably across consecutive ids.
        for (int pos = 0; pos < ShortCodeGenerator.CODE_LENGTH; pos++) {
            Set<Character> chars = new HashSet<>();
            for (long id = 1; id <= 200; id++) chars.add(generator.generate(id).charAt(pos));
            assertTrue(chars.size() > 40, "position " + pos + " barely varies: " + chars.size() + " distinct");
        }
    }

    @Test
    void rejectsIdsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(0));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(ShortCodeGenerator.MAX_IDS));
    }
}
