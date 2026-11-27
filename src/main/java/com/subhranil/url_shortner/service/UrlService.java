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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Short links. Every link has an owner ({@code userId}, taken from the caller's JWT). Redirects and
 * previews are public; listing, analytics and deleting only ever see the caller's own links, and a link
 * owned by someone else is reported as "not found" so its existence isn't revealed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {

    private static final String CACHE_PREFIX = "url:";
    private static final String CLICK_PREFIX = ClickCounter.KEY_PREFIX;

    private final UrlMappingRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ClickEventProducer clickEventProducer;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlNormalizer urlNormalizer;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Returns the caller's existing short link if they shortened this URL (after normalizing) before, so
     * each user gets one code per URL. Different users get separate links with separate click counts.
     * Not race-proof: two simultaneous requests for a new URL can still create two codes, which is harmless.
     */
    public ShortenResponse shortenUrl(long userId, String rawUrl) {
        String originalUrl = urlNormalizer.normalize(rawUrl);

        var existing = repository.findFirstByUserIdAndOriginalUrlAndShortCodeIsNotNullOrderByIdAsc(userId, originalUrl);
        if (existing.isPresent()) {
            String shortCode = existing.get().getShortCode();
            redisTemplate.opsForValue().set(CACHE_PREFIX + shortCode, originalUrl);
            return new ShortenResponse(shortCode, baseUrl + shortCode, originalUrl);
        }

        UrlMapping mapping = new UrlMapping();
        mapping.setUserId(userId);
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
            log.debug("Cache hit for {}", shortCode);
            return cached;
        }

        // fallback to database
        log.debug("Cache miss for {}, reading the database", shortCode);
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("Short URL not found: " + shortCode));

        // store in Redis for next time
        redisTemplate.opsForValue().set(CACHE_PREFIX + shortCode, mapping.getOriginalUrl());
        return mapping.getOriginalUrl();
    }

    public static final int MAX_PAGE_SIZE = 100;

    /** The caller's links, newest first. Click counts for the whole page come from Redis in one MGET. */
    public PagedResponse<UrlSummary> listUrls(long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<UrlMapping> result = repository.findByUserIdAndShortCodeIsNotNull(
                userId, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
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
        clickEventProducer.publish(shortCode);
    }

    public AnalyticsResponse getAnalytics(long userId, String shortCode) {
        UrlMapping mapping = findOwned(userId, shortCode);

        String clickCount = redisTemplate.opsForValue().get(CLICK_PREFIX + shortCode);
        long totalClicks = clickCount != null ? Long.parseLong(clickCount) : 0L;

        return new AnalyticsResponse(
                mapping.getShortCode(),
                mapping.getOriginalUrl(),
                totalClicks,
                mapping.getCreatedAt()
        );
    }

    /**
     * Deletes the caller's link, its cached target and its click counter. The code is never handed out
     * again, because codes come from database ids, which are never reused.
     * A redirect already in flight may re-cache the target a moment after the delete; that entry then
     * points at a deleted link until it is next overwritten. Rare, and harmless for a test-stage app.
     */
    @Transactional
    public void deleteUrl(long userId, String shortCode) {
        UrlMapping mapping = findOwned(userId, shortCode);
        repository.delete(mapping);
        redisTemplate.delete(List.of(CACHE_PREFIX + shortCode, CLICK_PREFIX + shortCode));
    }

    /** The caller's link, or 404 if it doesn't exist or belongs to someone else (same answer for both). */
    private UrlMapping findOwned(long userId, String shortCode) {
        return repository.findByShortCodeAndUserId(shortCode, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Short URL not found: " + shortCode));
    }
}
