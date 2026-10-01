package com.subhranil.url_shortner.dto;

import java.time.LocalDateTime;

public record AnalyticsResponse(
        String shortCode,
        String originalUrl,
        long totalClicks,
        LocalDateTime createdAt
) {
}