// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.cache.DeliveryTargetInformation;
import de.telekom.horizon.comet.client.RestClient;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.config.CometMetrics;
import de.telekom.horizon.comet.exception.CallbackException;
import de.telekom.horizon.comet.exception.CallbackUrlNotFoundException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.model.DeliveryResult;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static de.telekom.eni.pandora.horizon.metrics.HorizonMetricsConstants.*;

/**
 * The {@code DeliveryTask} class is responsible for executing the callback request for a single event message.
 *
 * The class handles a single {@link SubscriptionEventMessage} object.
 * For each {@link SubscriptionEventMessage} a task is created using the {@link DeliveryTaskFactory}.
 * If any task in the list fails, a nack (negative acknowledgment) for the failed message
 * and all following messages in the batch is sent to Kafka. All messages before the failed message are getting acknowledged.
 * If all tasks are successful, an acknowledgment for the batch is sent to Kafka.
 */
@Slf4j
public class DeliveryTask implements Runnable {
    private final SubscriptionEventMessage subscriptionEventMessage;

    private final String callbackUrlOrEmptyStr;

    private final RestClient restClient;

    private final long backoffInterval;

    private final int retryCount;

    private final CometConfig cometConfig;

    private final HorizonTracer tracer;

    private final HorizonMetricsHelper metricsHelper;

    private final CometMetrics cometMetrics;

    private final CircuitBreakerCacheService circuitBreakerCacheService;

    private final CallbackUrlCache callbackUrlCache;

    private final DeDuplicationService deDuplicationService;

    private final DeliveryResultListener deliveryResultListener;

    private Span deliverySpan;

    private final HorizonComponentId messageSource;
    private final ApplicationContext context;

    /**
     * Creates a new DeliveryTask instance.
     *
     * @param deliveryTaskRecord The DeliveryTaskRecord containing information about the current event message.
     */
    public DeliveryTask(DeliveryTaskRecord deliveryTaskRecord) {
        this.subscriptionEventMessage = deliveryTaskRecord.subscriptionEventMessage();
        this.callbackUrlOrEmptyStr = deliveryTaskRecord.callbackUrl();
        this.backoffInterval = deliveryTaskRecord.backoffInterval();
        this.retryCount = deliveryTaskRecord.retryCount();

        this.deliveryResultListener = deliveryTaskRecord.deliveryResultListener();

        this.restClient = deliveryTaskRecord.deliveryTaskFactory().getRestClient();
        this.cometConfig = deliveryTaskRecord.deliveryTaskFactory().getCometConfig();
        this.tracer = deliveryTaskRecord.deliveryTaskFactory().getTracer();
        this.metricsHelper = deliveryTaskRecord.deliveryTaskFactory().getMetricsHelper();
        this.cometMetrics = deliveryTaskRecord.deliveryTaskFactory().getCometMetrics();
        this.circuitBreakerCacheService = deliveryTaskRecord.deliveryTaskFactory().getCircuitBreakerCacheService();
        this.callbackUrlCache = deliveryTaskRecord.callbackUrlCache();
        this.deDuplicationService = deliveryTaskRecord.deliveryTaskFactory().getDeDuplicationService();
        this.deliverySpan = deliveryTaskRecord.deliverySpan();
        this.messageSource = deliveryTaskRecord.messageSource();
        this.context = deliveryTaskRecord.context();
    }

    /**
     * Executes the delivery task for the current event message.
     * This method handles exceptions thrown during the callback request and writes information about them into the current tracing span.
     * This method also handles the circuit breaker state for the current subscription.
     *
     * If the circuit breaker is open, the task waits for the configured backoff interval before returning.
     * If the circuit breaker is closed, the task executes the callback request and handles any exceptions thrown during the request.
     * If the callback request fails, the task determines whether the event should be redelivered based on the HTTP status code.
     * If the callback request succeeds, the task records the HTTP status code in metrics and finishes the current tracing span.
     */
    @Override
    public void run() {
        var status = Status.FAILED; // Failed if event not deliverable or internal error
        var shouldRedeliver = false;
        Exception exception = null;

        prepareDeliverySpan();

        try (var ignored = tracer.withSpanInScope(deliverySpan)) {
            log.debug("Start working on delivering message with id {}", subscriptionEventMessage.getUuid());

            addTracing();

            waitForBackoffPeriod();

            if (isCircuitBreakerOpenOrChecking(subscriptionEventMessage.getSubscriptionId())) {
                status = Status.WAITING;
                return; // jumps into finally block
            }

            executeCallback();

            status = Status.DELIVERED;

            processMetrics();
        } catch (CallbackException callbackException) { // -> Could send request, but status code != accepted
            shouldRedeliver = handleCallbackException(callbackException);
        } catch (CallbackUrlNotFoundException callbackUrlNotFoundException) {
            exception = callbackUrlNotFoundException;
            log.error("No callback url found for EventMessage with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(callbackUrlNotFoundException));
            deliverySpan.error(callbackUrlNotFoundException);
        } catch (InterruptedException interruptedException) {
            exception = interruptedException;
            shouldRedeliver = true; // Thread is occupied and could not finish, therefore we need to redeliver (may create duplicates)
            log.error("Thread interrupted while delivering event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(interruptedException));
            deliverySpan.error(interruptedException);
            Thread.currentThread().interrupt();
        } catch (JsonProcessingException jsonProcessingException) {
            exception = jsonProcessingException;
            log.error("Could not process json for event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(jsonProcessingException));
            deliverySpan.error(jsonProcessingException);
        } catch (IOException ioException) {
            exception = ioException;
            shouldRedeliver = true; // Maybe no firewall clearance, we still want to redeliver
            log.error("Error while sending http request to consumer for event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(ioException));
            deliverySpan.error(ioException);
        } catch (HazelcastInstanceNotActiveException hazelcastInstanceNotActiveException) {
            exception = hazelcastInstanceNotActiveException;
            log.error("Hazelcast was shutdown, wherefore the circuit-breaker could not be checked and we could not deliver event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(hazelcastInstanceNotActiveException));
            deliverySpan.error(hazelcastInstanceNotActiveException);
        } catch (CouldNotFetchAccessTokenException couldNotFetchAccessTokenException) {
            exception = couldNotFetchAccessTokenException;
            // This is a critical error, because we cannot deliver the event without an access token
            log.debug("CRITICAL: could not fetch access token for event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(couldNotFetchAccessTokenException));
        } catch (Exception unknownException) {
            exception = unknownException;
            log.error("Unknown exception occurred while processing event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(unknownException));
            deliverySpan.error(unknownException);
        } finally {
            if(exception != null) {
                writeInternalExceptionMetricTag(exception);
            }

            DeliveryResult deliveryResult = new DeliveryResult(subscriptionEventMessage, status, shouldRedeliver, exception, deliverySpan, messageSource);
            deliveryResultListener.handleDeliveryResult(deliveryResult);
            log.debug("Finished working on delivering message with id {}", subscriptionEventMessage.getUuid());
        }
    }

    private boolean isCircuitBreakerOpenOrChecking(String subscriptionId) {
        return circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId);
    }

    /**
     * Creates a new tracing span for the current event message.
     * This method sets tags in the current span related to the subscriptionId, subscriptionName, and event type.
     */
    private void prepareDeliverySpan() {
        if (Objects.isNull(deliverySpan)) {
            deliverySpan = tracer.startSpan("delivery");
            deliverySpan.kind(Span.Kind.CLIENT);
        }
    }

    /**
     * Annotates the current tracing span with information about the current retry attempt.
     * This method sets tags in the current span related to the retry attempt.
     */
    private void addTracing() {
        tracer.addTagsToSpanFromEventMessage(deliverySpan, subscriptionEventMessage);
        tracer.addTagsToSpanFromSubscriptionEventMessage(deliverySpan, subscriptionEventMessage);

        if (backoffInterval > 0) {
            deliverySpan.annotate(String.format("retry attempt %d", retryCount+1));
        }
    }

    /**
     * Waits for the configured backoff interval.
     *
     * @throws InterruptedException if the current thread is interrupted while sleeping
     */
    protected void waitForBackoffPeriod() throws InterruptedException {
        if (backoffInterval != 0) {
            var sleepingSpan = tracer.startScopedDebugSpan("backoff interval");
            sleepingSpan.tag("backoffInterval", String.valueOf(backoffInterval));
            sleepingSpan.annotate("wait for backoff period");

            try {
                TimeUnit.MILLISECONDS.sleep(backoffInterval);
            } finally {
                sleepingSpan.finish();
            }

            deliverySpan.annotate(String.format("waited %d milliseconds for backoff", backoffInterval));
        }
    }

    /**
     * Records the end-to-end latency for the current event message and extends the metadata for the current tracing span.
     */
    private void processMetrics() {
        writeHttpCodeMetricTags(HttpStatus.CREATED.value());
        cometMetrics.recordE2eEventLatencyAndExtendMetadata(subscriptionEventMessage, MetricNames.EndToEndLatencyCustomer, messageSource);
    }

    /**
     * Handles a callback exception by writing information about the exception into the current tracing span.
     * This method sets tags in the current span related to the HTTP code, exception cause, and redelivery status.
     *
     * @param callbackException The CallbackException to be handled.
     * @return True if the event should be redelivered after the callback exception; otherwise, false.
     */
    private boolean handleCallbackException(CallbackException callbackException) {
        var httpCode = callbackException.getStatusCode();
        writeHttpCodeMetricTags(httpCode);

        var retryableStatusCodesOptional = callbackUrlCache
                .getDeliveryTargetInformation(subscriptionEventMessage.getSubscriptionId())
                .map(DeliveryTargetInformation::getRetryableStatusCodes);

        var statusCodesToCheck = retryableStatusCodesOptional.orElse(cometConfig.getRedeliveryStatusCodes());

        boolean shouldRedeliver;
        if (retryableStatusCodesOptional.isPresent()) {
            shouldRedeliver = statusCodesToCheck.contains(httpCode);
        } else {
            shouldRedeliver = cometConfig.getRedeliveryStatusCodes().contains(httpCode);
        }

        var exceptionCause = callbackException.getCause();
        writeCallbackExceptionInTrace(exceptionCause, httpCode, shouldRedeliver);

        log.info("Response was not accepted for event with id {}: {}. Should redeliver: {}", subscriptionEventMessage.getUuid(),
                buildCauseDescription(exceptionCause, httpCode), shouldRedeliver);
        deliverySpan.error(callbackException);

        return shouldRedeliver;
    }

    /**
     * Executes the callback request for the current event message.
     * This method sets tags in the current span related to the callbackUrl.
     *
     * @throws CallbackException              if the callback request fails
     * @throws CallbackUrlNotFoundException  if no callback URL is found for the current event message
     * @throws IOException                    if an I/O error occurs while sending the callback request
     */
    private void executeCallback() throws CallbackException, CallbackUrlNotFoundException, IOException, CouldNotFetchAccessTokenException {
        deliverySpan.annotate("make callback request");

        var callbackSpan = tracer.startDebugSpan("callback");
        callbackSpan.kind(Span.Kind.CLIENT);

        try (var ignored = tracer.withDebugSpanInScope(callbackSpan)) {
            tracer.addTagsToSpanFromEventMessage(callbackSpan, subscriptionEventMessage);
            tracer.addTagsToSpanFromSubscriptionEventMessage(callbackSpan, subscriptionEventMessage);

            callbackSpan.tag("callbackUrl", callbackUrlOrEmptyStr);

            if (StringUtils.isBlank(callbackUrlOrEmptyStr)) {
                throw new CallbackUrlNotFoundException(String.format("No callback url found for EventMessage with id %s in cache and additional fields", subscriptionEventMessage.getUuid()));
            }

            log.info("Executing callback for EventMessage with id '{}' at '{}'", subscriptionEventMessage.getUuid(), callbackUrlOrEmptyStr);
            restClient.callback(subscriptionEventMessage, callbackUrlOrEmptyStr, context);
        } finally {
            callbackSpan.finish();
        }
    }

    /**
     * Writes information about a callback exception into the current tracing span.
     * This method sets tags in the current span related to the HTTP code, exception cause, and redelivery status.
     *
     * @param exceptionCause The Throwable representing the cause of the exception.
     * @param httpCode       The HTTP status code associated with the callback exception.
     * @param shouldRedeliver True if the event should be redelivered after the callback exception; otherwise, false.
     */
    public void writeCallbackExceptionInTrace(Throwable exceptionCause, int httpCode, boolean shouldRedeliver) {
        Span span = tracer.getCurrentSpan();
        if (span == null) { return; }

        if (!shouldRedeliver) {
            span.tag("statusCode", String.valueOf(httpCode));
            if(exceptionCause != null) {
                span.tag("cause",  exceptionCause.toString());
            }
        }

        span.tag("redelivery", String.valueOf(shouldRedeliver));
    }

    /**
     * Writes HTTP code metric tags and increments the corresponding counter metric.
     * This method is used to track the frequency of specific HTTP response codes for callback events.
     *
     * @param httpCode The HTTP status code to be recorded in metrics.
     */
    public void writeHttpCodeMetricTags(int httpCode) {
        var metricTags = metricsHelper.buildTagsFromSubscriptionEventMessage(subscriptionEventMessage)
                .and(TAG_HTTP_CODE, Integer.toString(httpCode));
        metricsHelper.getRegistry().counter(METRIC_CALLBACK_HTTP_CODE_COUNT, metricTags).increment();
    }

    /**
     * Writes metric tags for internal exceptions and increments the corresponding counter metric.
     * This method is used to track the frequency of internal exceptions in callback processing.
     *
     * @param exception The Exception to be recorded in metrics.
     */
    private void writeInternalExceptionMetricTag(Exception exception) {
        var metricTags = metricsHelper.buildTagsFromSubscriptionEventMessage(subscriptionEventMessage)
                .and("exception_class", exception.getClass().getName());
        metricsHelper.getRegistry().counter(METRIC_INTERNAL_EXCEPTION_COUNT, metricTags).increment();
    }

    /**
     * Builds a description for an exception cause along with the associated HTTP code.
     * If the exception cause is provided, the description includes information about the cause's class, message, and type.
     * If no exception cause is provided, the description defaults to "HTTP [httpCode]".
     *
     * @param exceptionCause The Throwable representing the cause of the exception.
     * @param httpCode       The HTTP status code associated with the callback exception.
     * @return A human-readable description of the exception cause.
     */
    public String buildCauseDescription(Throwable exceptionCause, int httpCode) {
        var causeDescription = "HTTP " + httpCode;
        if(exceptionCause != null) {
            causeDescription = buildCauseDescription(exceptionCause);
        }
        return causeDescription;
    }

    /**
     * Builds a description for an exception cause, including information about the cause's class, message, and type.
     * If no exception cause is provided, the description is an empty string.
     *
     * @param exceptionCause The Throwable representing the cause of the exception.
     * @return A human-readable description of the exception cause.
     */
    public String buildCauseDescription(Throwable exceptionCause) {
        var causeDescription = "";
        if (exceptionCause != null) {
            causeDescription =  String.format("cause %s %s with Type %s", exceptionCause, exceptionCause.getMessage(), exceptionCause.getClass().getName());
        }
        return causeDescription;
    }
}
