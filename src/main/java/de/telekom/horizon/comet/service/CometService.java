// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.eni.pandora.horizon.kubernetes.InformerStoreInitHandler;
import de.telekom.eni.pandora.horizon.kubernetes.SubscriptionResourceListener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ExitCodeEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
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

    private final ConcurrentMessageListenerContainer<String, String> messageListenerContainer;

    private final SubscriptionResourceListener subscriptionResourceListener;

    private final InformerStoreInitHandler informerStoreInitHandler;

    private final ApplicationContext applicationContext;


    /**
     * Constructs a CometService instance.
     *
     * @param messageListenerContainer   The Kafka message listener container.
     * @param subscriptionResourceListener Optional listener for subscription resources.
     * @param informerStoreInitHandler   Optional handler for initializing informer store.
     * @param applicationContext                    The application context.
     */
    public CometService(ConcurrentMessageListenerContainer<String, String> messageListenerContainer,
                       @Autowired(required = false) SubscriptionResourceListener subscriptionResourceListener,
                       @Autowired(required = false) InformerStoreInitHandler informerStoreInitHandler,
                       ApplicationContext applicationContext) {
        this.messageListenerContainer = messageListenerContainer;
        this.subscriptionResourceListener = subscriptionResourceListener;
        this.informerStoreInitHandler = informerStoreInitHandler;
        this.applicationContext = applicationContext;
    }


    /**
     * Initializes the CometService. If SubscriptionResourceListener is present, starts it and waits until
     * the informer store is fully synced before starting the message listener container.
     */
    @PostConstruct
    public void init() {
        if (subscriptionResourceListener != null) {
            subscriptionResourceListener.start();

            log.info("SubscriptionResourceListener started.");

            (new Thread(() -> {
                log.info("Waiting until Subscription resources are fully synced...");

                while(!informerStoreInitHandler.isFullySynced()) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException var6) {
                        break;
                    }
                }

                startMessageListenerContainer();
            })).start();
        } else {
            startMessageListenerContainer();
        }
    }

    /**
     * Starts the Kafka message listener container if it is not null.
     * This method is designed to initiate the consumption of Kafka messages.
     */
    private void startMessageListenerContainer() {
        if (messageListenerContainer != null) {
            messageListenerContainer.start();
            log.info("ConcurrentMessageListenerContainer started.");
        }
    }

    /**
     * Handles the application stopping event by stopping the Kafka message listener container.
     * This method is designed to gracefully stop the consumption of Kafka messages.
     */
    @EventListener(classes = {ExitCodeEvent.class, ContextClosedEvent.class})
    public void applicationStoppedHandler() {
        if (messageListenerContainer != null && messageListenerContainer.isRunning()) {
            messageListenerContainer.stop();

            log.info("Kafka message listener container stopped.");
        }
    }

    /**
     * Handles the event when the Kafka message listener container is stopped.
     * This method initiates the application exit process when the Kafka message listener container has been stopped.
     */
    @EventListener(classes = {ContainerStoppedEvent.class})
    public void containerStoppedHandler() {
        log.error("MessageListenerContainer stopped. Exiting...");

        System.exit(SpringApplication.exit(applicationContext, () -> 1));
    }
}
