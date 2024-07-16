// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.config.CometMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static de.telekom.horizon.comet.utils.MessageUtils.getMessageSource;

/**
 * The {@code SubscribedEventMessageHandler} subscribed events, processing observations, checking circuit breakers, and delivering events.
 * This class is responsible for consuming and processing subscribed events.
 */
@Service
@Slf4j
public class SubscribedEventMessageHandler {

    private final HorizonTracer tracer;

    private final DeliveryService deliveryService;

    private final CircuitBreakerCacheService circuitBreakerCacheService;

    private final StateService stateService;
    private final DeDuplicationService deDuplicationService;

    private final ObjectMapper objectMapper;
    private final CometMetrics cometMetrics;

    /**
     * Constructs a SubscribedEventMessageHandler with necessary dependencies.
     *
     * @param tracer                   The tracer for creating spans.
     * @param deliveryService          The service responsible for event delivery.
     * @param circuitBreakerCacheService The service for managing circuit breaker states.
     * @param stateService             The service for managing event states.
     * @param deDuplicationService     The service for handling duplicate events.
     * @param objectMapper             The ObjectMapper for JSON processing.
     * @param cometMetrics             The metrics service for recording event latency.
     */
    @Autowired
    public SubscribedEventMessageHandler(HorizonTracer tracer,
                                         DeliveryService deliveryService,
                                         CircuitBreakerCacheService circuitBreakerCacheService,
                                         StateService stateService,
                                         DeDuplicationService deDuplicationService,
                                         ObjectMapper objectMapper,
                                         CometMetrics cometMetrics) {
        this.tracer = tracer;
        this.deliveryService = deliveryService;
        this.circuitBreakerCacheService = circuitBreakerCacheService;
        this.stateService = stateService;
        this.deDuplicationService = deDuplicationService;
        this.objectMapper = objectMapper;
        this.cometMetrics = cometMetrics;
    }

    /**
     * Handles a message by starting spans, observations, checking the circuit breaker,
     * and setting the corresponding status. It also delivers the event in a new thread.
     *
     * @param consumerRecord The Kafka ConsumerRecord containing the message.
     * @return CompletableFuture with SendResult if delivery type is CALLBACK; otherwise, null.
     * @throws JsonProcessingException If there is an error processing the JSON.
     */
    public CompletableFuture<SendResult<String, String>> handleMessage(ConsumerRecord<String, String> consumerRecord) throws JsonProcessingException {
        log.warn("Start handling message with id {}", consumerRecord.key());
        var subscriptionEventMessage = objectMapper.readValue(consumerRecord.value(), SubscriptionEventMessage.class);

        DeliveryType deliveryType = subscriptionEventMessage.getDeliveryType();
        if (!Objects.equals(deliveryType, DeliveryType.CALLBACK)) {
            return null;
        }

        var clientId = getMessageSource(consumerRecord);

        CompletableFuture<SendResult<String, String>> afterStatusSendFuture;
        var rootSpan = tracer.startSpanFromKafkaHeaders("consume subscribed message", consumerRecord.headers());
        var rootSpanInScope = tracer.withSpanInScope(rootSpan); // try-with-resources not possible because scope closes after try -> we need context in catch

        log.warn("Start to handle Event with id {}", subscriptionEventMessage.getSubscriptionId());
        afterStatusSendFuture = handleEvent(subscriptionEventMessage, rootSpan, clientId);

        log.debug("Finished handling message with id {}", consumerRecord.key());
        rootSpanInScope.close();
        rootSpan.finish();

        return afterStatusSendFuture;
    }

    /**
     * Handles the subscription event message, processes observations, and determines whether
     * to deliver the event based on circuit breaker status and duplication checks.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to handle.
     * @param rootSpan                 The root span for the operation.
     * @param messageSource           The message source identified by HorizonComponentId.
     * @return CompletableFuture with SendResult based on event handling outcome.
     */
    public CompletableFuture<SendResult<String, String>> handleEvent(SubscriptionEventMessage subscriptionEventMessage, Span rootSpan, HorizonComponentId messageSource) {
        log.warn("Check circuitBreaker for subscriptionId {}", subscriptionEventMessage.getSubscriptionId());
        if (isCircuitBreakerOpenOrChecking(subscriptionEventMessage)) {
            rootSpan.annotate("Circuit Breaker open! Set event on WAITING");
            return stateService.updateState(Status.WAITING, subscriptionEventMessage, null);
        }

        log.warn("Check deduplication for subscriptionId {}", subscriptionEventMessage.getSubscriptionId());
        try {
            String msgUuidOrNull = deDuplicationService.get(subscriptionEventMessage);
            boolean isDuplicate = Objects.nonNull(msgUuidOrNull);
            if (isDuplicate) {
                log.warn("Event with id {} is a duplicate. Check if it is the same event.", subscriptionEventMessage.getUuid());
                // circuit breaker is not open AND event is a duplicate
                return handleDuplicateEvent(subscriptionEventMessage, msgUuidOrNull);
            }
        } catch (HazelcastInstanceNotActiveException ex) {
            log.warn("HazelcastInstanceNotActiveException occurred while checking for duplicate event with uuid {}. Event will be delivered anyways.", subscriptionEventMessage.getUuid(), ex);
            log.error("HazelcastInstanceNotActiveException occurred while checking for duplicate event with uuid {}. Event will be delivered anyways.", subscriptionEventMessage.getUuid(), ex);
            rootSpan.annotate("HazelcastInstanceNotActiveException occurred while checking for duplicate event. Event will be delivered anyways.");
            rootSpan.error(ex);
        }

        // circuit breaker is not open AND event is NO duplicate
        return deliverEvent(subscriptionEventMessage, messageSource);
    }

    /**
     * Checks if the circuit breaker is open or checking for the provided SubscriptionEventMessage.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to check the circuit breaker for.
     * @return True if the circuit breaker is open or checking; otherwise, false.
     */
    private boolean isCircuitBreakerOpenOrChecking(SubscriptionEventMessage subscriptionEventMessage) {
        return circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionEventMessage.getSubscriptionId());
    }


    /**
     * Sets the subscription event status to DELIVERING and initiates delivery.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to handle.
     * @param clientId                 The message source identified by HorizonComponentId.
     * @return CompletableFuture for the DELIVERING status sending
     */
    private CompletableFuture<SendResult<String, String>> deliverEvent(SubscriptionEventMessage subscriptionEventMessage, HorizonComponentId clientId){
        log.warn("Set event to DELIVERING and start delivery for subscriptionId {}", subscriptionEventMessage.getSubscriptionId());
        CompletableFuture<SendResult<String, String>> afterStatusSendFuture = stateService.updateState(Status.DELIVERING, subscriptionEventMessage, null);
        cometMetrics.recordE2eEventLatencyAndExtendMetadata(subscriptionEventMessage, MetricNames.EndToEndLatencyTardis, clientId);
        deliveryService.deliver(subscriptionEventMessage, clientId); // Starts async task in pool

        return afterStatusSendFuture;
    }

    /**
     * Handles a duplicate subscription event and updates status accordingly.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to handle.
     * @param msgUuidOrNull            The UUID of the duplicate message.
     * @return CompletableFuture with SendResult based on event handling outcome.
     */
    private CompletableFuture<SendResult<String, String>> handleDuplicateEvent(SubscriptionEventMessage subscriptionEventMessage, String msgUuidOrNull) {
        CompletableFuture<SendResult<String, String>> afterStatusSendFuture = null;

        if(Objects.equals(subscriptionEventMessage.getUuid(), msgUuidOrNull)) {
            log.debug("Message with id {} was found in the deduplication cache with the same UUID. Message will be ignored, because status will probably set to DELIVERED in the next minutes.", subscriptionEventMessage.getUuid());
        } else {
            log.debug("Message with id {} was found in the deduplication cache with another UUID. Message will be set to DUPLICATE to prevent event being stuck at PROCESSED.", subscriptionEventMessage.getUuid());
            afterStatusSendFuture =  stateService.updateState(Status.DUPLICATE, subscriptionEventMessage, null);
        }

        return afterStatusSendFuture;
    }

}
