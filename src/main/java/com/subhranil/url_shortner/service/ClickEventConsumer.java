package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.model.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClickEventConsumer {

    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "url-click-events", groupId = "analytics-group")
    public void consume(ClickEvent event) {
        redisTemplate.opsForValue().increment("clicks:" + event.getShortCode());
        log.info(">>> Kafka consumer processed click for shortCode={}",
                event.getShortCode());
    }
}
