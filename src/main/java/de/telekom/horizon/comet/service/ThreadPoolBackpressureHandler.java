package de.telekom.horizon.comet.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles backpressure by pausing and resuming the delivery thread pool based on utilization.
 */
@Slf4j
@Service
public class ThreadPoolBackpressureHandler implements RejectedExecutionHandler {

    @Value("${comet.backpressure.delivery-pool-utilization.resume-threshold:0.5}")
    private static final double RESUME_THRESHOLD = 0.5;

    private final ThreadPoolTaskExecutor deliveryTaskExecutor;
    private final MessageListenerContainer messageListenerContainer;


    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Counter pauseCounter;


    public ThreadPoolBackpressureHandler(MeterRegistry meterRegistry, ThreadPoolTaskExecutor deliveryTaskExecutor,
                                         MessageListenerContainer messageListenerContainer) {
        this.messageListenerContainer = messageListenerContainer;
        this.deliveryTaskExecutor = deliveryTaskExecutor;
        deliveryTaskExecutor.setRejectedExecutionHandler(this);

        this.pauseCounter = Counter.builder("pubsub.kafka.listener.pause.triggered")
                .description("Number of times the Kafka listener was paused due to backpressure")
                .register(meterRegistry);
        Gauge.builder("pubsub.kafka.listener.paused", paused, p -> p.get() ? 1.0 : 0.0)
                .description("Whether the Kafka listener is currently paused due to backpressure")
                .register(meterRegistry);
    }


    private double getDeliveryPoolUtilization() {
        var executor = deliveryTaskExecutor.getThreadPoolExecutor();
        int queueSize = executor.getQueue().size();
        int capacity = executor.getQueue().remainingCapacity() + queueSize;

        return capacity > 0 ? (double) queueSize / capacity : 0.0;
    }

    private void onBackpressureEvent(BackpressureEvent event) {
        switch (event) {
            case PAUSE -> {
                log.warn("Backpressure PAUSE - pausing Kafka listener container");
                messageListenerContainer.pause();
                log.info("Kafka listener container paused");
            }
            case RESUME -> {
                log.info("Backpressure RESUME - resuming Kafka listener container");
                messageListenerContainer.resume();
                log.info("Kafka listener container resumed");
            }
        }
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor threadPoolExecutor) {
        paused.set(true);
        onBackpressureEvent(BackpressureEvent.PAUSE);

        pauseCounter.increment();
        log.info("Kafka listener container paused due to thread pool saturation.");

        throw new RejectedExecutionException("Task " + runnable.toString() + " rejected from " + threadPoolExecutor.toString());
    }

    /**
     * Periodically monitors thread pool utilization and triggers backpressure events.
     * Runs every 500ms to detect saturation quickly without excessive overhead.
     */
    @Scheduled(fixedDelayString = "${comet.backpressure.delivery-pool-utilization.resume-check-interval-ms:1000}")
    public void monitorPoolUtilization() {
        if (!paused.get()) {
            return;
        }

        double utilization = getDeliveryPoolUtilization();
        if (utilization < RESUME_THRESHOLD) {
            paused.set(false);
            onBackpressureEvent(BackpressureEvent.RESUME);
            log.info("Kafka listener container resumed after thread pool capacity recovered.");
        }
    }

    enum BackpressureEvent {
        PAUSE, RESUME
    }

}
