package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.model.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Publishes click events without ever slowing down or breaking a redirect.
 *
 * <p>{@code send()} runs on the request thread and can block while the producer waits for topic metadata
 * or buffer space. {@code max.block.ms} in application*.yml caps that wait (500 ms instead of Kafka's
 * 60 s default). If the publish fails, synchronously or later in the background, the click is counted
 * directly in Redis instead, so analytics keep working while Kafka is down. This method never throws.
 *
 * <p>Trade-off: if Kafka stored the event but the acknowledgement timed out, the click can be counted
 * twice (once by the consumer, once by the fallback). That is rare, and better than losing clicks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickEventProducer {

    static final String TOPIC = "url-click-events";

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;
    private final ClickCounter clickCounter;

    public void publish(String shortCode) {
        try {
            kafkaTemplate.send(TOPIC, shortCode, new ClickEvent(shortCode, Instant.now()))
                    // Async: the fallback's Redis call must not run on Kafka's network thread.
                    .whenCompleteAsync((result, ex) -> {
                        if (ex != null) {
                            fallback(shortCode, ex);
                        }
                    });
        } catch (Exception ex) {
            fallback(shortCode, ex);
        }
    }

    private void fallback(String shortCode, Throwable cause) {
        log.warn("Kafka publish failed for shortCode={}, counting the click directly in Redis: {}",
                shortCode, cause.toString());
        try {
            clickCounter.increment(shortCode);
        } catch (Exception redisEx) {
            log.error("Click lost for shortCode={}: Kafka and Redis both failed", shortCode, redisEx);
        }
    }
}
