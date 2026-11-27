package com.subhranil.url_shortner.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class KafkaListenerStarterTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final KafkaListenerStarter starter = new KafkaListenerStarter(registry);

    @Test
    void startsStoppedListener() {
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        when(container.isRunning()).thenReturn(false);

        assertTrue(starter.tryStartAll());
        verify(container).start();
    }

    @Test
    void failedStartIsResetAndRetriedNextTime() {
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        when(container.isRunning()).thenReturn(false);
        doThrow(new RuntimeException("No resolvable bootstrap urls")).doNothing().when(container).start();

        assertFalse(starter.tryStartAll(), "first attempt fails while Kafka is down");
        verify(container).stop();

        assertTrue(starter.tryStartAll(), "second attempt succeeds once Kafka is back");
        verify(container, times(2)).start();
    }

    @Test
    void leavesRunningListenersAlone() {
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        when(container.isRunning()).thenReturn(true);

        assertTrue(starter.tryStartAll());
        verify(container, never()).start();
    }
}
