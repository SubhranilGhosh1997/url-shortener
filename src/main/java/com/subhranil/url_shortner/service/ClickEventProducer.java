package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.model.ClickEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ClickEventProducer {

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;

    public void publish(String shortCode) {
        kafkaTemplate.send("url-click-events", shortCode,
                new ClickEvent(shortCode, Instant.now()));
    }
}
