// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.test.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hazelcast.org.apache.commons.codec.digest.DigestUtils;
import de.telekom.eni.pandora.horizon.kafka.event.EventWriter;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.horizon.comet.cache.CallbackCacheProperties;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static de.telekom.horizon.comet.test.utils.WiremockStubs.stubOidc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
public abstract class AbstractIntegrationTest {

    static {
        EmbeddedKafkaHolder.getEmbeddedKafka();
    }

    public static final EmbeddedKafkaBroker broker = EmbeddedKafkaHolder.getEmbeddedKafka();

    @RegisterExtension
    public static WireMockExtension wireMockServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    public MockMvc mockmvc;

    @Autowired
    private EventWriter eventWriter;

    @Autowired
    private CallbackUrlCache callbackUrlCache;

    @Autowired
    private ConsumerFactory consumerFactory;

    private static final Map<String, BlockingQueue<ConsumerRecord<String, String>>> multiplexedRecordsMap = new HashMap<>();

    private KafkaMessageListenerContainer<String, String> container;

    private String eventType;

    @BeforeEach
    void setUp() {
        eventType = "junit.test.event." + DigestUtils.sha1Hex(String.valueOf(System.currentTimeMillis()));

        multiplexedRecordsMap.putIfAbsent(getEventType(), new LinkedBlockingQueue<>());

        var topicNames = Arrays.stream(EventRetentionTime.values()).map(EventRetentionTime::getTopic).toArray(String[]::new);
        var containerProperties = new ContainerProperties(topicNames);
        containerProperties.setGroupId("test-consumer-groupid"+getEventType());
        containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
        container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setBeanName("test-consumer-container");
        container.setupMessageListener((MessageListener<String, String>) record -> multiplexedRecordsMap.get(getEventType()).add(record));
        container.start();

        ContainerTestUtils.waitForAssignment(container, (EventRetentionTime.values().length - 1) * broker.getPartitionsPerTopic());
    }

    public void simulateNewPublishedEvent(SubscriptionEventMessage message) throws JsonProcessingException, ExecutionException, InterruptedException {
        eventWriter.send(Objects.requireNonNullElse(message.getEventRetentionTime(), EventRetentionTime.DEFAULT).getTopic(), message).get();
    }

    public void addTestSubscription(SubscriptionResource subscriptionResource) {
        callbackUrlCache.add(
                subscriptionResource.getSpec().getSubscription().getSubscriptionId(),
                new CallbackCacheProperties(subscriptionResource.getSpec().getSubscription().getCallback(), subscriptionResource.getSpec().getSubscription().isCircuitBreakerOptOut()));
    }

    public ConsumerRecord<String, String> pollForRecord(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return multiplexedRecordsMap.get(getEventType()).poll(timeout, timeUnit);
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.zipkin.enabled", () -> false);
        registry.add("spring.zipkin.baseUrl", () -> "http://localhost:9411");
        registry.add("horizon.kafka.bootstrapServers", broker::getBrokersAsString);
        registry.add("horizon.kafka.partitionCount", () -> 1);
        registry.add("horizon.kafka.maxPollRecords", () -> 1);
        registry.add("horizon.kafka.autoCreateTopics", () -> true);
        registry.add("horizon.cache.kubernetesServiceDns", () -> "");
        registry.add("horizon.cache.deDuplication.enabled", () -> true);
        registry.add("kubernetes.enabled", () -> false);
        registry.add("horizon.victorialog.enabled", () -> false);
        registry.add("comet.oidc.token-uri", () -> wireMockServer.baseUrl() + "/oidc");
        registry.add("comet.oidc.cronTokenFetch", () -> "-");
        registry.add("comet.callback.max-retries", () -> 2);
    }

    @BeforeAll
    static void beforeAll() {
        stubOidc(wireMockServer);
    }

    public String getEventType() {
        return eventType;
    }

}
