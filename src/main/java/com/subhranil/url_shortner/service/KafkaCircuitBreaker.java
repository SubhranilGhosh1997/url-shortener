package com.subhranil.url_shortner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Stops redirects from paying Kafka's timeout over and over during an outage.
 *
 * <ul>
 *   <li><b>Closed</b> (normal): every click goes to Kafka.</li>
 *   <li><b>Open</b>: after a failed publish, Kafka is skipped for {@link #OPEN_DURATION} and clicks are
 *       counted directly in Redis, so redirects don't wait on {@code max.block.ms} each time.</li>
 *   <li><b>Half-open</b>: once that time is up, exactly one click is let through as a trial. Success closes
 *       the circuit; failure opens it again for another {@link #OPEN_DURATION}.</li>
 * </ul>
 * Thread-safe without locks; it only needs to be approximately right.
 */
@Component
@Slf4j
public class KafkaCircuitBreaker {

    static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    private static final long CLOSED = Long.MIN_VALUE;

    private final long openNanos;
    private final LongSupplier nanoClock;

    /** Deadline (in nanoClock time) until which Kafka is skipped, or CLOSED. */
    private final AtomicLong openUntil = new AtomicLong(CLOSED);
    private final AtomicBoolean trialInFlight = new AtomicBoolean(false);

    public KafkaCircuitBreaker() {
        this(OPEN_DURATION, System::nanoTime);
    }

    KafkaCircuitBreaker(Duration openDuration, LongSupplier nanoClock) {
        this.openNanos = openDuration.toNanos();
        this.nanoClock = nanoClock;
    }

    /** True if this click should try Kafka. */
    public boolean allowRequest() {
        long until = openUntil.get();
        if (until == CLOSED) {
            return true;
        }
        if (nanoClock.getAsLong() - until < 0) {
            return false; // still open
        }
        return trialInFlight.compareAndSet(false, true); // half-open: one trial at a time
    }

    public void recordSuccess() {
        trialInFlight.set(false);
        if (openUntil.getAndSet(CLOSED) != CLOSED) {
            log.info("Kafka is reachable again: click events go through Kafka again");
        }
    }

    public void recordFailure() {
        long previous = openUntil.getAndSet(nanoClock.getAsLong() + openNanos);
        trialInFlight.set(false);
        if (previous == CLOSED) {
            log.warn("Kafka unavailable: skipping it for {}s and counting clicks directly in Redis",
                    OPEN_DURATION.toSeconds());
        }
    }

    public boolean isOpen() {
        return openUntil.get() != CLOSED;
    }
}
