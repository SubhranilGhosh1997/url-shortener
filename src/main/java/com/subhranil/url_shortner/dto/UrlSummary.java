package com.subhranil.url_shortner.dto;

import java.time.LocalDateTime;

public record UrlSummary(
        String shortCode,
        String shortUrl,
        String originalUrl,
        long totalClicks,
        LocalDateTime createdAt
) {
}
