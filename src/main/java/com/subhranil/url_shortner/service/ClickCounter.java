package com.subhranil.url_shortner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** The one place that knows how click counts are stored: a Redis counter per short code. */
@Component
@RequiredArgsConstructor
public class ClickCounter {

    public static final String KEY_PREFIX = "clicks:";

    private final StringRedisTemplate redisTemplate;

    public void increment(String shortCode) {
        redisTemplate.opsForValue().increment(KEY_PREFIX + shortCode);
    }
}
