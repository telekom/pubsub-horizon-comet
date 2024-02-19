// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config.kafka;

import de.telekom.eni.pandora.horizon.kafka.config.KafkaProperties;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.horizon.comet.kafka.KafkaConsumerRebalanceListener;
import de.telekom.horizon.comet.kafka.SubscribedEventMessageListener;
import de.telekom.horizon.comet.service.SubscribedEventMessageHandler;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Arrays;

/**
 * The Configuration {@code KafkaConsumerConfig} class for the Kafka consumer.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     *  This method is annotated with {@code @PreDestroy}, indicating that it should be
     *  called before the bean is destroyed. It is responsible for performing cleanup
     *  tasks related to the destruction of the Kafka consumer concurrentMessageListenerContainer.
     */
    @PreDestroy
    private void preDestroy() {
        log.info("Destroy kafka consumer concurrentMessageListenerContainer");
    }

    /**
     * Configures and creates a {@code ConcurrentMessageListenerContainer} for consuming Kafka messages.
     *
     * @param subscribedEventMessageHandler The handler for processing subscribed event messages.
     * @param props                         The Kafka properties' configuration.
     * @param consumerFactory               The factory for creating Kafka consumers.
     * @return The configured {@code ConcurrentMessageListenerContainer}.
     */
    @Autowired
    @DependsOn(value = {"hazelcastInstance"})
    @Bean
    public ConcurrentMessageListenerContainer<String, String> concurrentMessageListenerContainer(SubscribedEventMessageHandler subscribedEventMessageHandler,
                                                                                                 KafkaProperties props,
                                                                                                 ConsumerFactory<String, String> consumerFactory) {
        // Extract topic names from EventRetentionTime values
        var topicNames = Arrays.stream(EventRetentionTime.values()).
                map(EventRetentionTime::getTopic).
                distinct().
                toArray(String[]::new);

        // Set up container properties
        var containerProperties = new ContainerProperties(topicNames);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL);
        containerProperties.setConsumerRebalanceListener(new KafkaConsumerRebalanceListener());
        containerProperties.setMessageListener(new SubscribedEventMessageListener(subscribedEventMessageHandler));

        // Create ConcurrentMessageListenerContainer
        ConcurrentMessageListenerContainer<String, String> listenerContainer = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties);
        listenerContainer.setAutoStartup(false);
        listenerContainer.setConcurrency(props.getPartitionCount());

        // Set the bean name as the prefix  of the Kafka consumer thread name
        listenerContainer.setBeanName("kafka-message-listener");
        
        return listenerContainer;
    }
}
