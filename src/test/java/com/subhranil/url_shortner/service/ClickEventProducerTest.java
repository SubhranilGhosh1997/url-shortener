package com.subhranil.url_shortner.service;

import com.subhranil.url_shortner.model.ClickEvent;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ClickEventProducerTest {

    private KafkaTemplate<String, ClickEvent> kafkaTemplate;
    private ClickCounter clickCounter;
    private ClickEventProducer producer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        clickCounter = mock(ClickCounter.class);
        producer = new ClickEventProducer(kafkaTemplate, clickCounter);
    }

    @Test
    void successfulSendDoesNotUseFallback() {
        when(kafkaTemplate.send(eq(ClickEventProducer.TOPIC), eq("abc"), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        producer.publish("abc");

        verify(clickCounter, after(300).never()).increment(any());
    }

    @Test
    void failedSendCountsClickInRedis() {
        // What kafka-clients does when Kafka is unreachable: send() returns, the future fails later.
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("metadata not available")));

        producer.publish("abc");

        verify(clickCounter, timeout(2000)).increment("abc");
    }

    @Test
    void sendThrowingSynchronouslyCountsClickInRedis() {
        when(kafkaTemplate.send(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> producer.publish("abc"));

        verify(clickCounter).increment("abc");
    }

    @Test
    void neverThrowsEvenWhenKafkaAndRedisBothFail() {
        when(kafkaTemplate.send(any(), any(), any())).thenThrow(new RuntimeException("kafka down"));
        doThrow(new RuntimeException("redis down")).when(clickCounter).increment(any());

        assertDoesNotThrow(() -> producer.publish("abc"));
    }
}
