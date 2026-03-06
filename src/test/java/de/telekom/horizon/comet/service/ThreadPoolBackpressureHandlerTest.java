// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "horizon.kafka.consumerThreadpoolSize=2",
        "horizon.kafka.consumerQueueCapacity=2",
        "comet.backpressure.delivery-pool-utilization.resume-check-interval-ms=999999"
})
class ThreadPoolBackpressureHandlerTest extends AbstractIntegrationTest {

    @Autowired
    private ThreadPoolTaskExecutor deliveryTaskExecutor;

    @Autowired
    private MessageListenerContainer messageListenerContainer;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ThreadPoolBackpressureHandler backpressureHandler;

    private final List<Future<?>> tasks = new ArrayList<>();

    /**
     * Test that when the thread pool is saturated, tasks are rejected and the Kafka listener is paused
     */
    @Test
    void testListenerPausedWhenPoolUtilizationExhausted() {
        saturateThreadPool();

        // Verify queue is full
        assertEquals(deliveryTaskExecutor.getQueueCapacity(), deliveryTaskExecutor.getQueueSize(), "Queue should be full");

        // The task should be rejected, and the rejection handler should pause the listener
        assertThrows(RejectedExecutionException.class, () -> deliveryTaskExecutor.submit(() -> {
        }), "Task should be rejected");
        await().atMost(java.time.Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> {
                    assertTrue(messageListenerContainer.isPauseRequested(),
                            "Kafka Listener Container should be requested for pause but it isn't");
                });
    }

    /**
     * Test that when the thread pool utilization improves, the listener is resumed
     */
    @Test
    void testListenerResumedWhenUtilizationImproves() {
        // First, trigger a pause by filling the queue and causing a rejection
        saturateThreadPool();

        assertThrows(RejectedExecutionException.class, () -> deliveryTaskExecutor.submit(() -> {
        }), "Task should be rejected");

        await().atMost(java.time.Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> {
                    assertTrue(messageListenerContainer.isPauseRequested(),
                            "Kafka Listener Container should be requested for pause but it isn't");
                });

        cleanThreadPool();

        // Verify that the message listener container was resumed
        await().atMost(java.time.Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> {
                    backpressureHandler.monitorPoolUtilization();
                    assertFalse(messageListenerContainer.isPauseRequested(),
                            "Kafka Listener Container should be not requested for pause");
                });
    }

    /**
     * Test that the defined metrics (pauseCounter & gauge 'paused') are correctly working
     */
    @Test
    void testMetricsCorrectlyTracked() {
        // Trigger a pause by filling the queue and causing a rejection
        saturateThreadPool();
        assertThrows(RejectedExecutionException.class, () -> deliveryTaskExecutor.submit(() -> {
        }), "Task should be rejected");

        // Check that metrics have updated correctly
        double pausedGaugeValue = meterRegistry.get("pubsub.kafka.listener.paused").gauge().value();
        double pauseCounterValue = meterRegistry.get("pubsub.kafka.listener.pause.triggered").counter().count();

        assertEquals(1.0, pausedGaugeValue, "Paused gauge should be 1.0 when paused");
        assertEquals(1.0, pauseCounterValue, "Pause counter should 1.0");

        // Now trigger a resume and check metrics again
        cleanThreadPool();

        // Verify that the message listener container was resumed
        await().atMost(Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> {
                    backpressureHandler.monitorPoolUtilization();
                    assertFalse(messageListenerContainer.isContainerPaused(), "Kafka Listener Container should be resumed but is still paused");
                });

        double resumedGaugeValue = meterRegistry.get("pubsub.kafka.listener.paused").gauge().value();
        double finalCounterValue = meterRegistry.get("pubsub.kafka.listener.pause.triggered").counter().count();

        assertEquals(0.0, resumedGaugeValue, "Paused gauge should be 0.0 after resuming");
        assertEquals(1.0, finalCounterValue, "Pause counter should remain 1.0");
    }

    private void saturateThreadPool() {
        await().atMost(Duration.of(10, ChronoUnit.SECONDS))
                .pollInterval(Duration.of(10, ChronoUnit.MILLIS))
                .untilAsserted(() -> assertThrows(RejectedExecutionException.class, () -> {
                    Future<?> task = deliveryTaskExecutor.submit(() -> {
                        try {
                            Thread.sleep(300000);
                        } catch (InterruptedException e) {
                            // Task interrupted - exit gracefully
                        }
                        return null;
                    });
                    tasks.add(task);
                }));
    }

    private void cleanThreadPool() {
        tasks.forEach(task -> task.cancel(true));
    }

}
