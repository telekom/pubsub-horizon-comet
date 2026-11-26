// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
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
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

import static de.telekom.eni.pandora.horizon.metrics.HorizonMetricsConstants.*;

/**
 * The {@code DeliveryTask} class is responsible for executing the callback request for a single event message.
 * <p>
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

    private final DeliveryService deliveryService;

    private final Executor responseHandlerExecutor;

    private Span deliverySpan;

    private final HorizonComponentId messageSource;

    private final ResponseHandler responseHandler;

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
        this.deliveryService = deliveryTaskRecord.deliveryService();
        this.restClient = deliveryTaskRecord.deliveryTaskFactory().getRestClient();
        this.cometConfig = deliveryTaskRecord.deliveryTaskFactory().getCometConfig();
        this.tracer = deliveryTaskRecord.deliveryTaskFactory().getTracer();
        this.metricsHelper = deliveryTaskRecord.deliveryTaskFactory().getMetricsHelper();
        this.cometMetrics = deliveryTaskRecord.deliveryTaskFactory().getCometMetrics();
        this.circuitBreakerCacheService = deliveryTaskRecord.deliveryTaskFactory().getCircuitBreakerCacheService();
        this.callbackUrlCache = deliveryTaskRecord.callbackUrlCache();
        this.deliverySpan = deliveryTaskRecord.deliverySpan();
        this.messageSource = deliveryTaskRecord.messageSource();
        this.responseHandlerExecutor = deliveryTaskRecord.deliveryTaskFactory().getTaskExecutor();
        this.responseHandler = new ResponseHandler();
    }

    /**
     * Executes the delivery task for the current event message.
     * This method handles exceptions thrown during the callback request and writes information about them into the current tracing span.
     * This method also handles the circuit breaker state for the current subscription.
     * <p>
     * If the circuit breaker is open, the task waits for the configured backoff interval before returning.
     * If the circuit breaker is closed, the task executes the callback request and handles any exceptions thrown during the request.
     * If the callback request fails, the task determines whether the event should be redelivered based on the HTTP status code.
     * If the callback request succeeds, the task records the HTTP status code in metrics and finishes the current tracing span.
     */
    @Override
    public void run() {
        prepareDeliverySpan();

        try (var ignored = tracer.withSpanInScope(deliverySpan)) {
            log.debug("Start working on delivering message with id {}", subscriptionEventMessage.getUuid());
            addTracing();
            if (backoffInterval != 0) {
                deliverySpan.annotate(String.format("waited %d milliseconds for backoff", backoffInterval));
            }

            final var validationStatus = validate();
            switch (validationStatus) {
                case CircuitBreakerStatus.CLOSED, CallbackUrlValidationStatus.VALID:
                    deliver();
                    return;
                case CircuitBreakerStatus.OPEN:
                    deliveryService.updateDeliveryStateToWaiting(deliverySpan, subscriptionEventMessage);
                    return;
                case CallbackUrlValidationStatus.INVALID, CircuitBreakerStatus.ERROR:
                    deliveryService.updateDeliveryStateToFailed(deliverySpan, subscriptionEventMessage);
                    return;
                default:
                    throw new IllegalStateException("Unexpected value: " + validationStatus);
            }
        } catch (Exception unknownException) {
            log.error("Unknown exception occurred while processing event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(unknownException));
            deliverySpan.error(unknownException);
            writeInternalExceptionMetricTag(unknownException);
            deliveryService.updateDeliveryStateToFailed(deliverySpan, subscriptionEventMessage);
        }
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
            deliverySpan.annotate(String.format("retry attempt %d", retryCount + 1));
        }
    }

    private ValidationStatus validate() {
        final var statusAfterCircuitBreakerCheck = isCircuitBreakerOpenOrChecking(subscriptionEventMessage.getSubscriptionId());
        if (!statusAfterCircuitBreakerCheck.isValid()) {
            return statusAfterCircuitBreakerCheck;
        }

        return validateCallbackUrl();
    }

    private ValidationStatus isCircuitBreakerOpenOrChecking(String subscriptionId) {
        try {
            if (circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId)) {
                log.debug("Circuit breaker is open for subscriptionId {}, skipping delivery for event with id {}", subscriptionEventMessage.getSubscriptionId(), subscriptionEventMessage.getUuid());
                deliverySpan.annotate("Circuit Breaker open! Delivery skipped");
                deliverySpan.tag("result", "skipped due to open circuit breaker");
                deliverySpan.tag("reason", "circuit_breaker_open");

                return CircuitBreakerStatus.OPEN;
            }
        } catch (HazelcastInstanceNotActiveException hazelcastInstanceNotActiveException) {
            log.error("Hazelcast was shutdown, wherefore the circuit-breaker could not be checked and we could not deliver event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(hazelcastInstanceNotActiveException));
            deliverySpan.error(hazelcastInstanceNotActiveException);
            writeInternalExceptionMetricTag(hazelcastInstanceNotActiveException);
            return CircuitBreakerStatus.ERROR;
        }

        return CircuitBreakerStatus.CLOSED;
    }

    private ValidationStatus validateCallbackUrl() {
        if (StringUtils.isBlank(callbackUrlOrEmptyStr)) {
            final var callbackUrlNotFoundException = new CallbackUrlNotFoundException(String.format("No callback url found for EventMessage with id %s in cache and additional fields", subscriptionEventMessage.getUuid()));
            log.error("No callback url found for EventMessage with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(callbackUrlNotFoundException));
            deliverySpan.error(callbackUrlNotFoundException);
            writeInternalExceptionMetricTag(callbackUrlNotFoundException);

            final var deliveryTypeOfSubscription = callbackUrlCache
                    .getDeliveryTargetInformation(subscriptionEventMessage.getSubscriptionId())
                    .map(DeliveryTargetInformation::getDeliveryType);
            if (deliveryTypeOfSubscription.isPresent() &&
                    (deliveryTypeOfSubscription.get().equals("sse") || deliveryTypeOfSubscription.get().equals("server_sent_event"))) {
                log.warn("Event with id {} and deliveryType {} and error {} was skipped by deduplication",
                        subscriptionEventMessage.getUuid(),
                        deliveryTypeOfSubscription.orElse("unknown"),
                        callbackUrlNotFoundException.getClass().getSimpleName());
            }
            return CallbackUrlValidationStatus.INVALID;
        }

        return CallbackUrlValidationStatus.VALID;
    }

    private void deliver() {
        deliverySpan.annotate("make callback request");
        try {
            restClient.callback(subscriptionEventMessage, callbackUrlOrEmptyStr, responseHandler);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Could not process json for event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(jsonProcessingException));
            deliverySpan.error(jsonProcessingException);
            writeInternalExceptionMetricTag(jsonProcessingException);

            deliveryService.updateDeliveryStateToFailed(deliverySpan, subscriptionEventMessage);

        } catch (IOException ioException) {
            log.error("Error while preparing http request for event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(ioException));
            deliverySpan.error(ioException);
            writeInternalExceptionMetricTag(ioException);

            // Maybe no firewall clearance, we still want to redeliver
            deliveryService.redeliver(deliverySpan, subscriptionEventMessage, messageSource);

        } catch (CouldNotFetchAccessTokenException couldNotFetchAccessTokenException) {
            // This is a critical error, because we cannot deliver the event without an access token
            log.debug("CRITICAL: could not fetch access token for event with id {}: {}", subscriptionEventMessage.getUuid(), buildCauseDescription(couldNotFetchAccessTokenException));
            writeInternalExceptionMetricTag(couldNotFetchAccessTokenException);
        }
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
     * Writes information about a callback exception into the current tracing span.
     * This method sets tags in the current span related to the HTTP code, exception cause, and redelivery status.
     *
     * @param exceptionCause  The Throwable representing the cause of the exception.
     * @param httpCode        The HTTP status code associated with the callback exception.
     * @param shouldRedeliver True if the event should be redelivered after the callback exception; otherwise, false.
     */
    public void writeCallbackExceptionInTrace(Throwable exceptionCause, int httpCode, boolean shouldRedeliver) {
        Span span = tracer.getCurrentSpan();
        if (span == null) {
            return;
        }

        if (!shouldRedeliver) {
            span.tag("statusCode", String.valueOf(httpCode));
            if (exceptionCause != null) {
                span.tag("cause", exceptionCause.toString());
            }
        }

        span.tag("redelivery", String.valueOf(shouldRedeliver));
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
            causeDescription = String.format("cause %s %s with Type %s", exceptionCause, exceptionCause.getMessage(), exceptionCause.getClass().getName());
        }
        return causeDescription;
    }

    private class ResponseHandler implements FutureCallback<Message<HttpResponse, Void>> {

        @Override
        public void completed(Message<HttpResponse, Void> response) {
            responseHandlerExecutor.execute(() -> {
                final var successfulStatusCodes = cometConfig.getSuccessfulStatusCodes();
                final var statusCode = response.getHead().getCode();

                if (!successfulStatusCodes.contains(statusCode)) {
                    handleUnsuccessfulResponse(statusCode, response.getHead().getReasonPhrase());
                    return;
                }

                processMetrics();
                deliveryService.updateDeliveryStateToDelivered(deliverySpan, subscriptionEventMessage);
            });
        }

        @Override
        public void failed(Exception ex) {
            responseHandlerExecutor.execute(() -> handleException(ex));
        }

        @Override
        public void cancelled() {
            responseHandlerExecutor.execute(() -> handleException(new CancellationException("Delivery request to '" + callbackUrlOrEmptyStr + "' was cancelled")));
        }

        private void handleUnsuccessfulResponse(int statusCode, String reason) {
            writeHttpCodeMetricTags(statusCode);

            final var shouldRedeliver = callbackUrlCache
                    .getDeliveryTargetInformation(subscriptionEventMessage.getSubscriptionId())
                    .map(DeliveryTargetInformation::getRetryableStatusCodes)
                    .orElse(cometConfig.getRedeliveryStatusCodes())
                    .contains(statusCode);
            writeCallbackExceptionInTrace(null, statusCode, shouldRedeliver);
            log.info("Response was not accepted for event with id {}: {}. Should redeliver: {}", subscriptionEventMessage.getUuid(),
                    buildCauseDescription(statusCode), shouldRedeliver);
            deliverySpan.error(new CallbackException("Error while delivering event to callback '" + callbackUrlOrEmptyStr + "': " + reason, statusCode));

            if (shouldRedeliver) {
                deliveryService.redeliver(deliverySpan, subscriptionEventMessage, messageSource);
                return;
            }

            deliveryService.updateDeliveryStateToFailed(deliverySpan, subscriptionEventMessage);
        }

        private void handleException(final Exception exception) {
            log.error("Error while sending http request to consumer for event with id {}: {}",
                    subscriptionEventMessage.getUuid(),
                    "cause " + exception + " " + exception.getMessage() + " with Type " + exception.getClass().getName()
            );
            deliverySpan.error(exception);
            writeInternalExceptionMetricTag(exception);

            deliveryService.redeliver(deliverySpan, subscriptionEventMessage, messageSource);
        }

        /**
         * Builds a description for an exception cause along with the associated HTTP code.
         * If the exception cause is provided, the description includes information about the cause's class, message, and type.
         * If no exception cause is provided, the description defaults to "HTTP [httpCode]".
         *
         * @param httpCode       The HTTP status code associated with the callback exception.
         * @return A human-readable description of the exception cause.
         */
        private String buildCauseDescription(int httpCode) {
            return "HTTP " + httpCode;
        }

        /**
         * Records the end-to-end latency for the current event message and extends the metadata for the current tracing span.
         */
        private void processMetrics() {
            writeHttpCodeMetricTags(HttpStatus.CREATED.value());
            cometMetrics.recordE2eEventLatencyAndExtendMetadata(subscriptionEventMessage, MetricNames.EndToEndLatencyCustomer, messageSource);
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
    }

    private sealed interface ValidationStatus permits CircuitBreakerStatus, CallbackUrlValidationStatus {
        boolean isValid();
    }

    private enum CircuitBreakerStatus implements ValidationStatus {
        OPEN, CLOSED, ERROR;
        @Override
        public boolean isValid() {
            return switch (this) {
                case CLOSED -> true;
                case OPEN, ERROR -> false;
            };
        }
    }

    private enum CallbackUrlValidationStatus implements ValidationStatus {
        VALID, INVALID;
        @Override
        public boolean isValid() {
            return switch (this) {
                case VALID -> true;
                case INVALID -> false;
            };
        }
    }
}
