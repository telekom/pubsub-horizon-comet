package de.telekom.horizon.comet.service;

import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.model.event.Event;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.eni.pandora.horizon.victorialog.client.VictoriaLogClient;
import de.telekom.eni.pandora.horizon.victorialog.model.Observation;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.model.DeliveryResult;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The {@code DeliveryService} for handling event deliveries and managing delivery tasks.
 * This service is responsible for delivering subscription events, handling delivery results,
 * and managing delivery task execution, including redelivery and state updates.
 */
@Slf4j
@Service
public class DeliveryService implements DeliveryResultListener {

    private final ThreadPoolTaskExecutor deliveryTaskExecutor;

    private final ThreadPoolTaskExecutor redeliveryTaskExecutor;

    private final CometConfig cometConfig;

    private final HorizonTracer tracer;

    private final CallbackUrlCache callbackUrlCache;
    private final Map<String, Integer> retryCounts;

    private final StateService stateService;

    private final CircuitBreakerCacheService circuitBreakerCacheService;

    private final DeliveryTaskFactory deliveryTaskFactory;

    private final VictoriaLogClient victoriaLogClient;
    private final DeDuplicationService deDuplicationService;

    /**
     * Constructs a {@code DeliveryService} with necessary dependencies.
     *
     * @param cometConfig               The {@code CometConfig} instance for configuration.
     * @param tracer                    The {@code HorizonTracer} instance for creating spans.
     * @param callbackUrlCache          The {@code CallbackUrlCache} instance for caching callback URLs.
     * @param stateService              The {@code StateService} instance for managing event states.
     * @param circuitBreakerCacheService The {@code CircuitBreakerCacheService} instance for managing circuit breaker states.
     * @param deDuplicationService      The {@code DeDuplicationService} instance for handling duplicate events.
     * @param deliveryTaskFactory       The {@code DeliveryTaskFactory} instance for creating delivery tasks.
     * @param victoriaLogClient         The {@code VictoriaLogClient} instance for logging observations.
     * @param meterRegistry             The {@code MeterRegistry} for monitoring thread pool metrics.
     */
    @Autowired
    public DeliveryService(CometConfig cometConfig, HorizonTracer tracer, CallbackUrlCache callbackUrlCache, StateService stateService, CircuitBreakerCacheService circuitBreakerCacheService, DeDuplicationService deDuplicationService, DeliveryTaskFactory deliveryTaskFactory, VictoriaLogClient victoriaLogClient, MeterRegistry meterRegistry) {
        this.cometConfig = cometConfig;
        this.tracer = tracer;
        this.callbackUrlCache = callbackUrlCache;
        this.stateService = stateService;
        this.circuitBreakerCacheService = circuitBreakerCacheService;
        this.deliveryTaskFactory = deliveryTaskFactory;
        this.victoriaLogClient = victoriaLogClient;
        this.deDuplicationService = deDuplicationService;

        retryCounts = new HashMap<>();

        this.deliveryTaskExecutor = new ThreadPoolTaskExecutor();

        this.deliveryTaskExecutor.setAwaitTerminationSeconds(20);
        this.deliveryTaskExecutor.setCorePoolSize(cometConfig.getConsumerThreadPoolSize());
        this.deliveryTaskExecutor.setMaxPoolSize(cometConfig.getConsumerThreadPoolSize());
        this.deliveryTaskExecutor.setQueueCapacity(cometConfig.getConsumerQueueCapacity());
        this.deliveryTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        this.deliveryTaskExecutor.afterPropertiesSet();
        ExecutorServiceMetrics.monitor(meterRegistry, this.deliveryTaskExecutor.getThreadPoolExecutor(), "deliveryTaskExecutor", Collections.emptyList());

        this.redeliveryTaskExecutor = new ThreadPoolTaskExecutor();

        this.redeliveryTaskExecutor.setAwaitTerminationSeconds(20);
        this.redeliveryTaskExecutor.setCorePoolSize(cometConfig.getRedeliveryThreadPoolSize());
        this.redeliveryTaskExecutor.setMaxPoolSize(cometConfig.getRedeliveryThreadPoolSize());
        this.redeliveryTaskExecutor.setQueueCapacity(cometConfig.getRedeliveryQueueCapacity());
        this.redeliveryTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        this.redeliveryTaskExecutor.afterPropertiesSet();
        ExecutorServiceMetrics.monitor(meterRegistry, this.redeliveryTaskExecutor.getThreadPoolExecutor(), "redeliveryTaskExecutor", Collections.emptyList());
    }

    /**
     * Shuts down the delivery and redelivery task executors with a timeout to allow
     * graceful termination of running tasks.
     *
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
     * @param observation              The Observation associated with the event.
     * @param messageSource            The source of the message.
     */
    public void deliver(SubscriptionEventMessage subscriptionEventMessage, Observation observation, HorizonComponentId messageSource) {
        var callbackUrlOrEmptyStr = getCallbackUrlOrEmptyStr(subscriptionEventMessage);
        // if callbackUrl is empty, we throw an CallbackUrlNotFoundException later, which gets handled

        DeliveryTaskRecord deliveryTaskRecord = new DeliveryTaskRecord(subscriptionEventMessage, observation, callbackUrlOrEmptyStr, 0, 0, this, deliveryTaskFactory, victoriaLogClient, null, messageSource, null);
        var deliveryTask = deliveryTaskFactory.createNew(deliveryTaskRecord);

        // Do not wait for thread
        deliveryTaskExecutor.submit(tracer.withCurrentTraceContext(deliveryTask));
    }


    /**
     * Handles the delivery result, updating the state and managing redelivery if necessary.
     *
     * This method processes the information contained in the {@code DeliveryResult} and takes appropriate actions
     * such as updating the delivery status, handling redelivery attempts, and managing circuit breaker states.
     *
     * @param deliveryResult The {@code DeliveryResult} containing information about the delivery outcome.
     *                       It includes the delivery status, subscription event message, observation,
     *                       and a flag indicating whether redelivery should be attempted.
     */
    @Override
    public void handleDeliveryResult(DeliveryResult deliveryResult) {
        var status = deliveryResult.status();
        var subscriptionEventMessage = deliveryResult.subscriptionEventMessage();
        var observation = deliveryResult.observation();
        var shouldRedeliver = deliveryResult.shouldRedeliver();

        // Status can only be FAILED or DELIVERED
        // shouldRedeliver is only true on FAILED with CallbackException, InterruptedException, HazelcastNotActiveException or IOException
        if (shouldRedeliver) {
            status = Status.DELIVERING;
            var isRedelivering = tryToRedeliver(deliveryResult);
            if (!isRedelivering) {
                if (isOptedOutFromCircuitBreaker(subscriptionEventMessage.getSubscriptionId())) {
                    status = Status.FAILED;
                } else {
                    status = Status.WAITING;
                    openCircuitBreaker(subscriptionEventMessage);
                }
            }
        }

        // Status can be FAILED, DELIVERED, DELIVERING or WAITING, we don't want to set DELIVERING,
        // because it was set before the acknowledgment. Therefore, only write state != DELIVERING
        if (!status.equals(Status.DELIVERING) && !(deliveryResult.exception() instanceof CouldNotFetchAccessTokenException)) {
            retryCounts.remove(subscriptionEventMessage.getUuid());

            updateDeliveryState(status, subscriptionEventMessage, observation, deliveryResult);
        }
    }

    /**
     * Updates the delivery state based on the status and other information from the DeliveryResult.
     * This method also tracks the event for deduplication when necessary.
     *
     * @param status                   The final status of the delivery.
     * @param subscriptionEventMessage The SubscriptionEventMessage associated with the delivery.
     * @param observation              The Observation associated with the event.
     * @param deliveryResult           The DeliveryResult containing information about the delivery outcome.
     */
    private void updateDeliveryState(Status status, SubscriptionEventMessage subscriptionEventMessage, Observation observation, DeliveryResult deliveryResult) {
        try (var ignored = tracer.withSpanInScope(deliveryResult.deliverySpan())) {
            var afterSendFuture = stateService.updateState(status, subscriptionEventMessage, deliveryResult.exception());
            if (status.equals(Status.DELIVERED) || status.equals(Status.FAILED)) {
                afterSendFuture.thenAccept(result -> trackEventForDeduplication(subscriptionEventMessage));
            }

            addFinishAndCountObservationCallback(subscriptionEventMessage, observation, afterSendFuture);
        } finally {
            deliveryResult.deliverySpan().finish();
        }
    }

    /**
     * Checks if a subscription has opted out from the circuit breaker based on the subscription ID.
     *
     * @param subscriptionId The ID of the subscription to check.
     * @return True if the subscription has opted out from the circuit breaker; otherwise, false.
     */
    private boolean isOptedOutFromCircuitBreaker(String subscriptionId) {
        return callbackUrlCache.get(subscriptionId).isOptOutCircuitBreaker();
    }

    /**
     * Adds a callback to the CompletableFuture to finish the observation and count the event in VictoriaLogClient.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage associated with the delivery.
     * @param observation              The Observation associated with the event.
     * @param future                   The CompletableFuture representing the asynchronous result of the state update.
     */
    private void addFinishAndCountObservationCallback(SubscriptionEventMessage subscriptionEventMessage, Observation observation, CompletableFuture<SendResult<String, String>> future) {
        var event = subscriptionEventMessage.getEvent();

        future.whenComplete((result, ex) -> finishAndCountObservation(observation, event));
    }

    /**
     * Finishes the observation and adds it to VictoriaLogClient. Also, counts the event in VictoriaLogClient.
     *
     * @param observation The Observation to finish and add.
     * @param event       The Event associated with the observation.
     */
    private void finishAndCountObservation(Observation observation, Event event) {
        victoriaLogClient.finishAndAddObservation(observation);
        victoriaLogClient.countEvent(event);
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
        var callbackUrlCacheEntry = callbackUrlCache.get(subscriptionEventMessage.getSubscriptionId());
        if (callbackUrlCacheEntry != null) {
            return Optional.ofNullable(callbackUrlCacheEntry.getUrl()).orElse("");
        }
        return "";
    }

    /**
     * Tries to redeliver an event with exponential backoff in the redelivery thread pool.
     *
     * @param deliveryResult The DeliveryResult containing information about the original delivery.
     * @return True if redelivery is scheduled; otherwise, false if the maximum retries are reached.
     */
    private boolean tryToRedeliver(DeliveryResult deliveryResult) {
        var subscriptionEventMessage = deliveryResult.subscriptionEventMessage();
        var observation = deliveryResult.observation();
        var deliverySpan = deliveryResult.deliverySpan();

        var eventUuid = subscriptionEventMessage.getUuid();
        var retryCount = Optional.ofNullable(retryCounts.get(eventUuid)).orElse(0);
        if (retryCount >= cometConfig.getMaxRetries()) {
            log.info("Retries for event with id {} exhausted (Retry {}/{})", eventUuid, retryCount, cometConfig.getMaxRetries());
            deliverySpan.annotate("retries exhausted!");
            return false;
        }

        var backoffInterval = increaseAndGetBackOffInterval(eventUuid); // increases retryCount by 1
        var callbackUrlOrEmptyStr = getCallbackUrlOrEmptyStr(subscriptionEventMessage);

        log.info("Scheduling redelivery task for event with id {} and a delay of {}ms (Retry {}/{})",
                eventUuid, backoffInterval, retryCount, cometConfig.getMaxRetries());


        DeliveryTaskRecord deliveryTaskRecord = new DeliveryTaskRecord(subscriptionEventMessage, observation, callbackUrlOrEmptyStr, backoffInterval, retryCount, this, deliveryTaskFactory, victoriaLogClient, deliverySpan, deliveryResult.messageSource(), null);
        var redeliverTask = deliveryTaskFactory.createNew(deliveryTaskRecord);
        redeliveryTaskExecutor.submit(tracer.withCurrentTraceContext(redeliverTask));
        return true;
    }

    /**
     * Opens the circuit breaker for a subscription event.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage for which to open the circuit breaker.
     */
    private void openCircuitBreaker(SubscriptionEventMessage subscriptionEventMessage) {
        circuitBreakerCacheService.openCircuitBreaker(subscriptionEventMessage.getSubscriptionId(),
                getCallbackUrlOrEmptyStr(subscriptionEventMessage),
                subscriptionEventMessage.getEnvironment());
    }
}

