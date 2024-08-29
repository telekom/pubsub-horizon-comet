// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.horizon.comet.client.TokenFetchingFailureEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ContainerStoppedEvent;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code CometService} class is responsible for managing Comet-related functionalities, including Kafka message
 * listening and synchronization of subscription resources.
 *
 * This service is specifically designed to handle asynchronous events related to Comet features.
 */
@Slf4j
@Service
public class CometService {

    private final ConcurrentMessageListenerContainer<String, String> messageListenerContainer;

    private final ApplicationContext context;

    private final AtomicBoolean teardownOngoing = new AtomicBoolean(false);


    /**
     * Constructs a CometService instance.
     *
     * @param messageListenerContainer   The Kafka message listener container.
     * @param context                    The application context.
     */
    public CometService(ConcurrentMessageListenerContainer<String, String> messageListenerContainer,
                       ApplicationContext context) {
        this.messageListenerContainer = messageListenerContainer;
        this.context = context;
    }

    @PreDestroy
    public void tearDown() {
        if (teardownOngoing.compareAndSet(false, true)) {
            stopMessageListenerContainer();
        }
    }


    /**
     * Starts the Kafka message listener container if it is not null.
     * This method is designed to initiate the consumption of Kafka messages.
     */
    @PostConstruct
    public void init() {
        if (messageListenerContainer != null) {
            messageListenerContainer.start();
            log.info("ConcurrentMessageListenerContainer started.");
        }
    }

    /**
     * Stops the Kafka message listener container if it is not null and running.
     * This method is designed to gracefully stop the consumption of Kafka messages.
     */
    private void stopMessageListenerContainer() {
        log.info("Stop kafka message listener container");

        if (messageListenerContainer != null) {
            messageListenerContainer.stop();
        }
    }

    /**
     * Handles the application stopping event by stopping the Kafka message listener container.
     *
     * @param event The application event triggering the handler.
     *              It should be an instance of {@code ContextClosedEvent} or {@code ExitCodeEvent}.
     */
    @EventListener
    public void applicationEventHandler(ApplicationEvent event) {
        if (event instanceof TokenFetchingFailureEvent) {
            log.error(((TokenFetchingFailureEvent) event).getMessage());
            stopMessageListenerContainer();
        }
    }

    /**
     * Handles the event when the Kafka message listener container is stopped.
     * This method gracefully stops the message listener container and initiates the application exit process.
     *
     * @param event The {@code ContainerStoppedEvent} triggered when the Kafka container is stopped.
     */
    @EventListener
    public void containerStoppedHandler(ContainerStoppedEvent event) {
        if (!teardownOngoing.get()) {
            log.error("MessageListenerContainer stopped unexpectedly with event {}. Exiting...", event.toString());

            var exitCode= SpringApplication.exit(context, () -> 1);

            System.exit(exitCode);
        } else {
            log.info("MessageListenerContainer stopped with event {}.", event.toString());
        }
    }
}
