package com.subhranil.url_shortner.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class KafkaCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final KafkaCircuitBreaker breaker = new KafkaCircuitBreaker(Duration.ofNanos(100), now::get);

    @Test
    void closedByDefault() {
        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowRequest());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void failureOpensCircuitUntilTimeIsUp() {
        breaker.recordFailure();

        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowRequest());
        now.addAndGet(99);
        assertFalse(breaker.allowRequest(), "still inside the open window");
    }

    @Test
    void afterOpenWindowOnlyOneTrialIsLetThrough() {
        breaker.recordFailure();
        now.addAndGet(100);

        assertTrue(breaker.allowRequest(), "the trial request");
        assertFalse(breaker.allowRequest(), "others keep skipping Kafka while the trial is in flight");
    }

    @Test
    void successfulTrialClosesCircuit() {
        breaker.recordFailure();
        now.addAndGet(100);
        breaker.allowRequest();

        breaker.recordSuccess();

        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowRequest());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void failedTrialReopensForAnotherWindow() {
        breaker.recordFailure();
        now.addAndGet(100);
        breaker.allowRequest();

        breaker.recordFailure();

        assertFalse(breaker.allowRequest());
        now.addAndGet(100);
        assertTrue(breaker.allowRequest(), "next trial after the new window");
    }

    @Test
    void worksWhenNanoClockIsNegative() {
        now.set(-5_000); // System.nanoTime() may be negative
        breaker.recordFailure();
        assertFalse(breaker.allowRequest());
        now.addAndGet(100);
        assertTrue(breaker.allowRequest());
    }
}
