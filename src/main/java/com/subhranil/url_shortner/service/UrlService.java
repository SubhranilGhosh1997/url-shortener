package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.dto.AnalyticsResponse;
import com.subhranil.url_shortner.dto.ShortenResponse;
import com.subhranil.url_shortner.exception.ResourceNotFoundException;
import com.subhranil.url_shortner.model.UrlMapping;
import com.subhranil.url_shortner.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UrlService {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final String CACHE_PREFIX = "url:";
    private static final String CLICK_PREFIX = "clicks:";

    private final UrlMappingRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ClickEventProducer clickEventProducer;

    @Value("${app.base-url}")
    private String baseUrl;

    public ShortenResponse shortenUrl(String originalUrl) {
        UrlMapping mapping = new UrlMapping();
        mapping.setOriginalUrl(originalUrl);
        mapping.setCreatedAt(LocalDateTime.now());
        mapping = repository.save(mapping);

        String shortCode = encodeBase62(mapping.getId());
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

    public void recordClick(String shortCode) {
//        redisTemplate.opsForValue().increment(CLICK_PREFIX + shortCode);
        clickEventProducer.publish(shortCode);
    }

    private String encodeBase62(long id) {
        if (id == 0) return String.valueOf(ALPHABET.charAt(0));
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % 62)));
            id /= 62;
        }
        return sb.reverse().toString();
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
