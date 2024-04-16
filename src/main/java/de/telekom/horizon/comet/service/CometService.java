// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.eni.pandora.horizon.exception.CouldNotStartInformerException;
import de.telekom.eni.pandora.horizon.kubernetes.ListenerEvent;
import de.telekom.eni.pandora.horizon.kubernetes.SubscriptionResourceListener;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ExitCodeEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ContainerStoppedEvent;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.stereotype.Service;

/**
 * The {@code CometService} class is responsible for managing Comet-related functionalities, including Kafka message
 * listening and synchronization of subscription resources.
 *
 * This service is specifically designed to handle asynchronous events related to Comet features.
 */
@Slf4j
@Service
public class CometService {

    private final ApplicationContext applicationContext;

    private final CallbackUrlCache callbackUrlCache;

    private final ConcurrentMessageListenerContainer<String, String> messageListenerContainer;

    private final SubscriptionResourceListener subscriptionResourceListener;

    /**
     * Constructs a CometService instance.
     *
     * @param messageListenerContainer   The Kafka message listener container.
     * @param subscriptionResourceListener Optional listener for subscription resources.
     * @param informerStoreInitHandler   Optional handler for initializing informer store.
     * @param applicationContext                    The application context.
     */
    public CometService(ApplicationContext applicationContext, CallbackUrlCache callbackUrlCache, ConcurrentMessageListenerContainer<String, String> messageListenerContainer,
                        SubscriptionResourceListener subscriptionResourceListener) {
        this.applicationContext = applicationContext;
        this.callbackUrlCache = callbackUrlCache;
        this.messageListenerContainer = messageListenerContainer;
        this.subscriptionResourceListener = subscriptionResourceListener;
    }

    /**
     * Initializes the CometService. If SubscriptionResourceListener is present, starts it and waits until
     * the informer store is fully synced before starting the message listener container.
     */
    @PostConstruct
    public void init() {
        if (subscriptionResourceListener != null) {
            try {
                subscriptionResourceListener.start();
            } catch (CouldNotStartInformerException e) {
                log.error(e.getMessage(), e);
                SpringApplication.exit(applicationContext, () -> 1);
            }
        }

        if (messageListenerContainer != null) {
            messageListenerContainer.start();

            log.info("ConcurrentMessageListenerContainer started.");
        }
    }

    @EventListener
    public void handleSubscriptionResourceListenerEvent(ListenerEvent e) {
        if (e.getType() == ListenerEvent.Type.SUBSCRIPTION_RESOURCE_LISTENER) {
            switch (e.getEvent()) {
                case INFORMER_STARTED -> {
                    log.info("Received INFORMER_STARTED event from SubscriptionResourceListener.");

                    callbackUrlCache.setHealthy();
                }
                case INFORMER_STOPPED -> {
                    log.error("Received INFORMER_STOPPED event from SubscriptionResourceListener, terminating.");

                    SpringApplication.exit(applicationContext, () -> 2);
                }
            }
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

    @EventListener
    public void applicationContextEventHandler(ApplicationContextEvent event) {
        if (event instanceof ContextStoppedEvent){

            log.info("Context stopped event");
            stopMessageListenerContainer();
        } else if (event instanceof ContextClosedEvent){
            log.info("Context closed event");
            stopMessageListenerContainer();
        }
    }

    @EventListener
    public void exitCodeEventHandler(ExitCodeEvent event) {
        log.info("Exit code event");

        var exitCode = event.getExitCode();
        if (exitCode > 0) {
            log.info("Exit code {}", exitCode);

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
    public void kafkaContainerStoppedEventHandler(ContainerStoppedEvent event) {
        log.error("MessageListenerContainer stopped with event {}. Exiting...", event.toString());

        stopMessageListenerContainer();

        SpringApplication.exit(applicationContext, () -> 3);
    }
}
