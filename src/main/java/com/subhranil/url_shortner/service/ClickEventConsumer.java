package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.model.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClickEventConsumer {

    private final ClickCounter clickCounter;

    @KafkaListener(topics = ClickEventProducer.TOPIC, groupId = "analytics-group")
    public void consume(ClickEvent event) {
        clickCounter.increment(event.getShortCode());
        log.info(">>> Kafka consumer processed click for shortCode={}",
                event.getShortCode());
    }
}
