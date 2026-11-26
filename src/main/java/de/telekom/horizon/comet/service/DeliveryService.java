// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.cache.DeliveryTargetInformation;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@code DeliveryService} for handling event deliveries and managing delivery tasks.
 * This service is responsible for delivering subscription events, handling delivery results,
 * and managing delivery task execution, including redelivery and state updates.
 */
@Slf4j
@Service
public class DeliveryService {

    private final ThreadPoolTaskExecutor deliveryTaskExecutor;

    private final ScheduledExecutorService redeliveryTaskExecutor;

    private final CometConfig cometConfig;

    private final HorizonTracer tracer;

    private final CallbackUrlCache callbackUrlCache;

    private final Map<String, Integer> retryCounts;

    private final StateService stateService;

    private final CircuitBreakerCacheService circuitBreakerCacheService;

    private final DeliveryTaskFactory deliveryTaskFactory;

    private final DeDuplicationService deDuplicationService;


    /**
     * Constructs a {@code DeliveryService} with necessary dependencies.
     *
     * @param cometConfig                The {@code CometConfig} instance for configuration.
     * @param tracer                     The {@code HorizonTracer} instance for creating spans.
     * @param callbackUrlCache           The {@code CallbackUrlCache} instance for caching callback URLs.
     * @param stateService               The {@code StateService} instance for managing event states.
     * @param circuitBreakerCacheService The {@code CircuitBreakerCacheService} instance for managing circuit breaker states.
     * @param deDuplicationService       The {@code DeDuplicationService} instance for handling duplicate events.
     * @param deliveryTaskFactory        The {@code DeliveryTaskFactory} instance for creating delivery tasks.
     */
    @Autowired
    public DeliveryService(CometConfig cometConfig, HorizonTracer tracer, CallbackUrlCache callbackUrlCache, StateService stateService,
                           CircuitBreakerCacheService circuitBreakerCacheService, DeDuplicationService deDuplicationService,
                           DeliveryTaskFactory deliveryTaskFactory,
                           @Qualifier("deliveryTaskExecutor") ThreadPoolTaskExecutor deliveryTaskExecutor,
                           @Qualifier("redeliveryExecutorService") ScheduledExecutorService redeliveryTaskExecutor) {
        this.cometConfig = cometConfig;
        this.tracer = tracer;
        this.callbackUrlCache = callbackUrlCache;
        this.stateService = stateService;
        this.circuitBreakerCacheService = circuitBreakerCacheService;
        this.deliveryTaskFactory = deliveryTaskFactory;
        this.deDuplicationService = deDuplicationService;

        retryCounts = new HashMap<>();

        this.deliveryTaskExecutor = deliveryTaskExecutor;
        this.redeliveryTaskExecutor = redeliveryTaskExecutor;
    }

    /**
     * Shuts down the delivery and redelivery task executors with a timeout to allow
     * graceful termination of running tasks.
     * <p>
     * This method is annotated with {@code @PreDestroy} to ensure it is called when
     * the bean is being destroyed.
     */
    @PreDestroy
    private void stopTaskExecutorWithTimeout() {
        deliveryTaskExecutor.shutdown();
        redeliveryTaskExecutor.shutdown();
    }

    /**
     * Initiates the delivery of a subscription event.
     * Creates a new delivery task and submits it to the main delivery task executor.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to be delivered.
     * @param messageSource            The source of the message.
     */
    public void deliver(SubscriptionEventMessage subscriptionEventMessage, HorizonComponentId messageSource) {
        var callbackUrlOrEmptyStr = getCallbackUrlOrEmptyStr(subscriptionEventMessage);
        // if callbackUrl is empty, we throw an CallbackUrlNotFoundException later, which gets handled

        DeliveryTaskRecord deliveryTaskRecord = new DeliveryTaskRecord(
                subscriptionEventMessage, callbackUrlOrEmptyStr, 0, 0, this,
                deliveryTaskFactory, callbackUrlCache, null, messageSource, null
        );
        var deliveryTask = deliveryTaskFactory.createNew(deliveryTaskRecord);

        // Do not wait for thread
        deliveryTaskExecutor.submit(tracer.withCurrentTraceContext(deliveryTask));
    }


    private void runDeliveryStateUpdate(Span deliverySpan, String subscriptionEventMessageUuid, Runnable updateFn) {
        retryCounts.remove(subscriptionEventMessageUuid);
        try (var ignored = tracer.withSpanInScope(deliverySpan)) {
            updateFn.run();
        } finally {
            deliverySpan.finish();
        }
    }

    public void updateDeliveryStateToDelivered(Span deliverySpan, SubscriptionEventMessage subscriptionEventMessage) {
        runDeliveryStateUpdate(
                deliverySpan,
                subscriptionEventMessage.getUuid(),
                () -> stateService
                        .updateState(Status.DELIVERED, subscriptionEventMessage, null)
                        .thenAccept(result -> trackEventForDeduplication(subscriptionEventMessage))
        );
    }

    public void updateDeliveryStateToFailed(Span deliverySpan, SubscriptionEventMessage subscriptionEventMessage) {
        runDeliveryStateUpdate(
                deliverySpan,
                subscriptionEventMessage.getUuid(),
                () -> stateService.updateState(Status.FAILED, subscriptionEventMessage, null)
        );
    }

    public void updateDeliveryStateToWaiting(Span deliverySpan, SubscriptionEventMessage subscriptionEventMessage) {
        runDeliveryStateUpdate(
                deliverySpan,
                subscriptionEventMessage.getUuid(),
                () -> stateService.updateState(Status.WAITING, subscriptionEventMessage, null)
        );
    }

    /**
     * Checks if a subscription has opted out from the circuit breaker based on the subscription ID.
     *
     * @param subscriptionId The ID of the subscription to check.
     * @return True if the subscription has opted out from the circuit breaker; otherwise, false.
     */
    private boolean isOptedOutFromCircuitBreaker(String subscriptionId) {
        return callbackUrlCache.getDeliveryTargetInformation(subscriptionId)
                .map(DeliveryTargetInformation::isOptOutCircuitBreaker)
                .orElse(false);
    }

    /**
     * Tracks an event with its key in the deduplication cache.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to track for deduplication.
     */
    private void trackEventForDeduplication(SubscriptionEventMessage subscriptionEventMessage) {
        try {
            var oldValue = deDuplicationService.track(subscriptionEventMessage);
            log.debug("Message with id {} was DELIVERED/FAILED and {} tracked ({}) already for deduplication!", subscriptionEventMessage.getUuid(), Objects.nonNull(oldValue) ? "was" : "was NOT", oldValue);
        } catch (HazelcastInstanceNotActiveException hazelcastInstanceNotActiveException) {
            log.error("Hazelcast shutdown, wherefore we could not track deduplication for event with id {}", subscriptionEventMessage.getUuid(), hazelcastInstanceNotActiveException);
        } catch (Exception ex) {
            log.error("Unknown error, could not track deduplication for event with id {}!", subscriptionEventMessage.getUuid(), ex);
        }
    }

    /**
     * Calculates exponential backoff based on {@link CometConfig} and increases the retry count.
     *
     * @param uuid The UUID of the event for which to calculate the backoff interval.
     * @return The calculated backoff interval in milliseconds.
     */
    private long increaseAndGetBackOffInterval(String uuid) {
        int retries = Optional.ofNullable(retryCounts.get(uuid)).orElse(0);
        retryCounts.put(uuid, retries + 1);

        return Math.min(Math.round(Math.pow(cometConfig.getBackoffMultiplier(), retries) * cometConfig.getInitialBackoffIntervalInMs()), cometConfig.getMaxBackoffIntervalInMs());
    }

    /**
     * Tries to get the current callbackUrl from the callbackUrlCache. If not available, use additional fields.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage for which to get the callbackUrl.
     * @return The callbackUrl obtained from the cache or additional fields.
     */
    private String getCallbackUrlOrEmptyStr(SubscriptionEventMessage subscriptionEventMessage) {
        var callbackUrlCacheEntry = callbackUrlCache.getDeliveryTargetInformation(subscriptionEventMessage.getSubscriptionId());
        return callbackUrlCacheEntry.map(deliveryTargetInformation ->
                Optional.ofNullable(deliveryTargetInformation.getUrl()).orElse("")).orElse("");
    }

    public void redeliver(Span deliverySpan, SubscriptionEventMessage subscriptionEventMessage, HorizonComponentId horizonComponentId) {
        final var eventUuid = subscriptionEventMessage.getUuid();
        final var retryCount = Optional.ofNullable(retryCounts.get(eventUuid)).orElse(0);
        if (retryCount >= cometConfig.getMaxRetries()) {
            log.info("Retries for event with id {} exhausted (Retry {}/{})", eventUuid, retryCount, cometConfig.getMaxRetries());
            deliverySpan.annotate("retries exhausted!");
            updateDeliveryStateOnRedeliveryFailure(deliverySpan, subscriptionEventMessage);
            return;
        }

        final var backoffInterval = increaseAndGetBackOffInterval(eventUuid); // increases retryCount by 1
        final var callbackUrlOrEmptyStr = getCallbackUrlOrEmptyStr(subscriptionEventMessage);

        log.info("Scheduling redelivery task for event with id {} and a delay of {}ms (Retry {}/{})",
                eventUuid, backoffInterval, retryCount, cometConfig.getMaxRetries());

        DeliveryTaskRecord deliveryTaskRecord = new DeliveryTaskRecord(
                subscriptionEventMessage, callbackUrlOrEmptyStr, backoffInterval, retryCount, this,
                deliveryTaskFactory, callbackUrlCache, deliverySpan, horizonComponentId, null
        );
        final var redeliverTask = deliveryTaskFactory.createNew(deliveryTaskRecord);

        try {
            redeliveryTaskExecutor.schedule(tracer.withCurrentTraceContext(redeliverTask), backoffInterval, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Unable to schedule redelivery for the message with uuid {}", subscriptionEventMessage.getUuid(), e);
            updateDeliveryStateOnRedeliveryFailure(deliverySpan, subscriptionEventMessage);
        }
    }

    private void updateDeliveryStateOnRedeliveryFailure(Span deliverySpan, SubscriptionEventMessage subscriptionEventMessage) {
        if (isOptedOutFromCircuitBreaker(subscriptionEventMessage.getSubscriptionId())) {
            updateDeliveryStateToFailed(deliverySpan, subscriptionEventMessage);
        } else {
            updateDeliveryStateToWaiting(deliverySpan, subscriptionEventMessage);
            openCircuitBreaker(subscriptionEventMessage);
        }
    }

    /**
     * Opens the circuit breaker for a subscription event.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage for which to open the circuit breaker.
     */
    private void openCircuitBreaker(SubscriptionEventMessage subscriptionEventMessage) {
        circuitBreakerCacheService.openCircuitBreaker(
                subscriptionEventMessage.getSubscriptionId(),
                subscriptionEventMessage.getEvent().getType(),
                subscriptionEventMessage.getUuid(),
                subscriptionEventMessage.getEnvironment());
    }
}
