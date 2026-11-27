package com.subhranil.url_shortner.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts the Kafka listeners after the app is up, retrying until Kafka is reachable.
 *
 * <p>Listeners have {@code spring.kafka.listener.auto-startup: false}. With auto-startup, Spring builds the
 * consumers during startup, and if the broker's host name can't be resolved (e.g. the Kafka container is
 * stopped) the consumer constructor throws and the whole app refuses to start. Starting them here instead
 * means a Kafka outage only delays click consumption; redirects keep working and clicks are counted
 * through ClickEventProducer's Redis fallback in the meantime.
 */
@Component
@Slf4j
public class KafkaListenerStarter {

    static final long RETRY_SECONDS = 10;

    private final KafkaListenerEndpointRegistry registry;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kafka-listener-starter");
        t.setDaemon(true);
        return t;
    });

    public KafkaListenerStarter(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWhenKafkaIsReachable() {
        executor.scheduleWithFixedDelay(this::tryStartAll, 0, RETRY_SECONDS, TimeUnit.SECONDS);
    }

    /** One attempt to start every listener that isn't running. Returns true when all are running. */
    boolean tryStartAll() {
        boolean allRunning = true;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                continue;
            }
            try {
                container.start();
                log.info("Kafka listener '{}' started", container.getListenerId());
            } catch (Exception ex) {
                allRunning = false;
                log.warn("Kafka listener '{}' not started yet, retrying in {}s: {}",
                        container.getListenerId(), RETRY_SECONDS, rootCause(ex));
                try {
                    container.stop(); // reset a half-started container so the next attempt really retries
                } catch (Exception ignored) {
                    // nothing more to clean up
                }
            }
        }
        if (allRunning) {
            executor.shutdown();
        }
        return allRunning;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static String rootCause(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }
}
