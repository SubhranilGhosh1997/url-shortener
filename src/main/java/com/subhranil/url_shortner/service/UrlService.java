package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.dto.AnalyticsResponse;
import com.subhranil.url_shortner.dto.PagedResponse;
import com.subhranil.url_shortner.dto.UrlSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.subhranil.url_shortner.dto.ShortenResponse;
import com.subhranil.url_shortner.exception.ResourceNotFoundException;
import com.subhranil.url_shortner.model.UrlMapping;
import com.subhranil.url_shortner.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UrlService {

    private static final String CACHE_PREFIX = "url:";
    private static final String CLICK_PREFIX = "clicks:";

    private final UrlMappingRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ClickEventProducer clickEventProducer;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlNormalizer urlNormalizer;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Returns the existing short link if this URL (after normalizing) was shortened before, so each URL
     * gets one code. When duplicates from before this rule exist, the oldest one wins.
     * Not race-proof: two simultaneous requests for a new URL can still create two codes, which is harmless.
     * Once user accounts exist, reuse should be per user (same URL + same owner).
     */
    public ShortenResponse shortenUrl(String rawUrl) {
        String originalUrl = urlNormalizer.normalize(rawUrl);

        var existing = repository.findFirstByOriginalUrlAndShortCodeIsNotNullOrderByIdAsc(originalUrl);
        if (existing.isPresent()) {
            String shortCode = existing.get().getShortCode();
            redisTemplate.opsForValue().set(CACHE_PREFIX + shortCode, originalUrl);
            return new ShortenResponse(shortCode, baseUrl + shortCode, originalUrl);
        }

        UrlMapping mapping = new UrlMapping();
        mapping.setOriginalUrl(originalUrl);
        mapping.setCreatedAt(LocalDateTime.now());
        mapping = repository.save(mapping);

        String shortCode = shortCodeGenerator.generate(mapping.getId());
        mapping.setShortCode(shortCode);
        repository.save(mapping);

        redisTemplate.opsForValue().set(CACHE_PREFIX + shortCode, originalUrl);

        return new ShortenResponse(shortCode, baseUrl + shortCode, originalUrl);
    }

    public String resolveUrl(String shortCode) {
        // check Redis first
        String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + shortCode);
        if (cached != null) {
            System.out.println(">>> CACHE HIT for: " + shortCode);
            return cached;
        }

        // fallback to database
        System.out.println(">>> CACHE MISS - hitting database for: " + shortCode);
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("Short URL not found: " + shortCode));

        // store in Redis for next time
        redisTemplate.opsForValue().set(CACHE_PREFIX + shortCode, mapping.getOriginalUrl());
        return mapping.getOriginalUrl();
    }

    public static final int MAX_PAGE_SIZE = 100;

    /** Newest first. Click counts for the whole page come from Redis in one MGET. */
    public PagedResponse<UrlSummary> listUrls(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<UrlMapping> result = repository.findByShortCodeIsNotNull(
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        List<UrlMapping> mappings = result.getContent();

        List<String> clickKeys = mappings.stream().map(m -> CLICK_PREFIX + m.getShortCode()).toList();
        List<String> counts = clickKeys.isEmpty() ? List.of() : redisTemplate.opsForValue().multiGet(clickKeys);

        List<UrlSummary> items = new ArrayList<>(mappings.size());
        for (int i = 0; i < mappings.size(); i++) {
            UrlMapping m = mappings.get(i);
            String count = counts != null ? counts.get(i) : null;
            items.add(new UrlSummary(
                    m.getShortCode(),
                    baseUrl + m.getShortCode(),
                    m.getOriginalUrl(),
                    count != null ? Long.parseLong(count) : 0L,
                    m.getCreatedAt()));
        }
        return new PagedResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    public ShortenResponse preview(String shortCode) {
        return new ShortenResponse(shortCode, baseUrl + shortCode, resolveUrl(shortCode));
    }

    public void recordClick(String shortCode) {
//        redisTemplate.opsForValue().increment(CLICK_PREFIX + shortCode);
        clickEventProducer.publish(shortCode);
    }

    public AnalyticsResponse getAnalytics(String shortCode) {

        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Short URL not found: " + shortCode
                        ));

        String clickCount = redisTemplate.opsForValue()
                .get(CLICK_PREFIX + shortCode);

        long totalClicks = clickCount != null
                ? Long.parseLong(clickCount)
                : 0L;

        return new AnalyticsResponse(
                mapping.getShortCode(),
                mapping.getOriginalUrl(),
                totalClicks,
                mapping.getCreatedAt()
        );
    }
}
