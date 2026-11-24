// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.DebugSpanWrapper;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.client.RestClient;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.config.CometMetrics;
import de.telekom.horizon.comet.exception.CallbackUrlNotFoundException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.model.DeliveryResult;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import de.telekom.horizon.comet.test.utils.ObjectGenerator;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.util.List;

import static de.telekom.eni.pandora.horizon.metrics.HorizonMetricsConstants.METRIC_CALLBACK_HTTP_CODE_COUNT;
import static de.telekom.eni.pandora.horizon.metrics.HorizonMetricsConstants.METRIC_INTERNAL_EXCEPTION_COUNT;
import static de.telekom.eni.pandora.horizon.metrics.HorizonMetricsConstants.TAG_HTTP_CODE;
import static de.telekom.horizon.comet.test.utils.ObjectGenerator.TEST_SUBSCRIPTION_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryTaskTest {

    private static final String CALLBACK_URL = "https://localhost/artificial/testing/url/callback";

    @Mock
    private CometConfig cometConfig;
    @Mock
    private RestClient restClient;
    @Mock
    private StateService stateService;
    @Mock
    private CircuitBreakerCacheService circuitBreakerCacheService;
    @Mock
    private DeDuplicationService deDuplicationService;
    @Mock
    private HorizonTracer tracer;
    @Mock
    private HorizonMetricsHelper metricsHelper;
    @Mock
    private CometMetrics cometMetrics;
    @Mock
    private CallbackUrlCache callbackUrlCache;
    @Mock
    private DeliveryResultListener deliveryResultListener;
    @Mock
    private Span deliverySpan;
    @Mock
    private ThreadPoolTaskExecutor executor;

    private DeliveryTaskFactory deliveryTaskFactory;
    private SubscriptionEventMessage eventMessage;

    @BeforeEach
    void setUp() {
        deliveryTaskFactory = new DeliveryTaskFactory(
                cometConfig,
                restClient,
                stateService,
                circuitBreakerCacheService,
                deDuplicationService,
                tracer,
                metricsHelper,
                cometMetrics,
                executor
        );
        eventMessage = ObjectGenerator.generateCallbackSubscriptionEventMessage();
    }

    @Test
    @DisplayName("Skip callback and emit WAITING result on initial delivery when CircuitBreaker is OPEN")
    void shouldSkipCallbackAndEmitWaitingOnInitialDeliveryWhenCircuitBreakerOpen() {
        // given
        when(tracer.startSpan("delivery")).thenReturn(deliverySpan);
        when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(true);

        var record = new DeliveryTaskRecord(
                eventMessage,
                CALLBACK_URL,
                0L,
                0,
                deliveryResultListener,
                deliveryTaskFactory,
                callbackUrlCache,
                null,
                HorizonComponentId.UNSET,
                null
        );
        DeliveryTask task = deliveryTaskFactory.createNew(record);

        // when
        task.run();

        // then
        verify(tracer).startSpan("delivery");
        verify(deliverySpan).kind(Span.Kind.CLIENT);
        verify(tracer).withSpanInScope(deliverySpan);
        verify(tracer).addTagsToSpanFromEventMessage(deliverySpan, eventMessage);
        verify(tracer).addTagsToSpanFromSubscriptionEventMessage(deliverySpan, eventMessage);

        verify(deliverySpan, never()).annotate(startsWith("retry attempt"));
        verify(deliverySpan, never()).annotate(startsWith("waited "));

        verify(circuitBreakerCacheService).isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID);
        verify(deliverySpan).annotate("Circuit Breaker open! Delivery skipped");
        verify(deliverySpan).tag("result", "skipped due to open circuit breaker");
        verify(deliverySpan).tag("reason", "circuit_breaker_open");
        verify(deliverySpan, never()).finish();

        verifyNoInteractions(restClient, cometMetrics, metricsHelper, callbackUrlCache, executor);

        var resultCaptor = ArgumentCaptor.forClass(DeliveryResult.class);
        verify(deliveryResultListener).handleDeliveryResult(resultCaptor.capture());

        DeliveryResult result = resultCaptor.getValue();
        assertEquals(eventMessage, result.subscriptionEventMessage());
        assertEquals(Status.WAITING, result.status());
        assertFalse(result.shouldRedeliver());
        assertNull(result.exception());
        assertEquals(deliverySpan, result.deliverySpan());
        assertEquals(HorizonComponentId.UNSET, result.messageSource());
    }

    @Test
    @DisplayName("Reuse span, annotate backoff, skip callback, and emit WAITING on redelivery when CircuitBreaker is OPEN")
    void shouldReuseSpanRecordBackoffAndEmitWaitingOnRedeliveryWhenCircuitBreakerOpen() {
        // given
        long backoffInterval = 1500L;
        int retryCount = 2;
        when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(true);

        var record = new DeliveryTaskRecord(
                eventMessage,
                CALLBACK_URL,
                backoffInterval,
                retryCount,
                deliveryResultListener,
                deliveryTaskFactory,
                callbackUrlCache,
                deliverySpan,
                HorizonComponentId.UNSET,
                null
        );
        DeliveryTask task = deliveryTaskFactory.createNew(record);

        // when
        task.run();

        // then
        verify(tracer, never()).startSpan(anyString());
        verify(deliverySpan, never()).kind(any());
        verify(tracer).withSpanInScope(deliverySpan);
        verify(tracer).addTagsToSpanFromEventMessage(deliverySpan, eventMessage);
        verify(tracer).addTagsToSpanFromSubscriptionEventMessage(deliverySpan, eventMessage);

        verify(deliverySpan).annotate("retry attempt 3");
        verify(deliverySpan).annotate("waited 1500 milliseconds for backoff");
        verify(deliverySpan).annotate("Circuit Breaker open! Delivery skipped");
        verify(deliverySpan).tag("result", "skipped due to open circuit breaker");
        verify(deliverySpan).tag("reason", "circuit_breaker_open");
        verify(deliverySpan, never()).finish();

        verifyNoInteractions(restClient, cometMetrics, metricsHelper, callbackUrlCache, executor);

        verify(deliveryResultListener).handleDeliveryResult(new DeliveryResult(
                eventMessage,
                Status.WAITING,
                false,
                null,
                deliverySpan,
                HorizonComponentId.UNSET
        ));
    }

    @Nested
    @DisplayName("CircuitBreaker is NOT OPEN")
    class CircuitBreakerNotOpenTest {

        @Mock
        private DebugSpanWrapper callbackSpan;

        private SimpleMeterRegistry meterRegistry;

        @BeforeEach
        void setUpRestClient() throws IOException, CouldNotFetchAccessTokenException {
            meterRegistry = new SimpleMeterRegistry();
            setupRestClientWithResponseCallback();
        }

        @Test
        @DisplayName("Execute callback, record metrics, and emit DELIVERED on initial delivery when CircuitBreaker is closed")
        void shouldExecuteCallbackAndEmitDeliveredOnInitialDeliveryWhenCircuitBreakerClosed() throws Exception {
            // given
            when(tracer.startSpan("delivery")).thenReturn(deliverySpan);
            when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(false);
            when(tracer.startDebugSpan("callback")).thenReturn(callbackSpan);
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    CALLBACK_URL,
                    0L,
                    0,
                    deliveryResultListener,
                    deliveryTaskFactory,
                    callbackUrlCache,
                    null,
                    HorizonComponentId.UNSET,
                    null
            );
            DeliveryTask task = deliveryTaskFactory.createNew(record);

            // when
            task.run();

            // then
            verify(tracer).startSpan("delivery");
            verify(deliverySpan).kind(Span.Kind.CLIENT);
            verify(deliverySpan, never()).annotate(startsWith("retry attempt"));
            verify(deliverySpan, never()).annotate(startsWith("waited "));
            verify(deliverySpan, never()).annotate("Circuit Breaker open! Delivery skipped");
            verify(deliverySpan).annotate("make callback request");

            verify(tracer).startDebugSpan("callback");
            verify(callbackSpan).kind(Span.Kind.CLIENT);
            verify(tracer).withDebugSpanInScope(callbackSpan);
            verify(tracer).addTagsToSpanFromEventMessage(callbackSpan, eventMessage);
            verify(tracer).addTagsToSpanFromSubscriptionEventMessage(callbackSpan, eventMessage);
            verify(callbackSpan).tag("callbackUrl", CALLBACK_URL);
            verify(restClient).callback(eq(eventMessage), eq(CALLBACK_URL), any());
            verify(callbackSpan).finish();

            assertEquals(1.0, meterRegistry.get(METRIC_CALLBACK_HTTP_CODE_COUNT)
                    .tag(TAG_HTTP_CODE, String.valueOf(HttpStatus.CREATED.value()))
                    .counter()
                    .count());
            assertNull(meterRegistry.find(METRIC_INTERNAL_EXCEPTION_COUNT).counter());
            verify(cometMetrics).recordE2eEventLatencyAndExtendMetadata(
                    eventMessage, MetricNames.EndToEndLatencyCustomer, HorizonComponentId.UNSET
            );

            verify(deliveryResultListener).handleDeliveryResult(new DeliveryResult(
                    eventMessage,
                    Status.DELIVERED,
                    false,
                    null,
                    deliverySpan,
                    HorizonComponentId.UNSET
            ));
        }

        @Test
        @DisplayName("Reuse span, annotate backoff, execute callback, and emit DELIVERED on redelivery when CircuitBreaker is closed")
        void shouldReuseSpanAndExecuteCallbackOnRedeliveryWhenCircuitBreakerClosed() throws Exception {
            // given
            long backoffInterval = 1500L;
            int retryCount = 2;
            when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(false);
            when(tracer.startDebugSpan("callback")).thenReturn(callbackSpan);
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    CALLBACK_URL,
                    backoffInterval,
                    retryCount,
                    deliveryResultListener,
                    deliveryTaskFactory,
                    callbackUrlCache,
                    deliverySpan,
                    HorizonComponentId.UNSET,
                    null
            );
            DeliveryTask task = deliveryTaskFactory.createNew(record);

            // when
            task.run();

            // then
            verify(tracer, never()).startSpan(anyString());
            verify(deliverySpan).annotate("retry attempt 3");
            verify(deliverySpan).annotate("waited 1500 milliseconds for backoff");
            verify(deliverySpan).annotate("make callback request");
            verify(restClient).callback(eq(eventMessage), eq(CALLBACK_URL), any());
            verify(callbackSpan).finish();

            verify(deliveryResultListener).handleDeliveryResult(new DeliveryResult(
                    eventMessage,
                    Status.DELIVERED,
                    false,
                    null,
                    deliverySpan,
                    HorizonComponentId.UNSET
            ));
        }

        @Test
        @DisplayName("Skip callback, record exception metric, and emit FAILED when CircuitBreaker check throws HazelcastInstanceNotActiveException")
        void shouldEmitFailedWithoutCallbackWhenCircuitBreakerCheckThrowsHazelcastInactive() {
            // given
            var hazelcastException = new HazelcastInstanceNotActiveException("Hazelcast inactive");
            reset(restClient, executor, cometConfig);
            when(tracer.startSpan("delivery")).thenReturn(deliverySpan);
            when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenThrow(hazelcastException);
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    CALLBACK_URL,
                    0L,
                    0,
                    deliveryResultListener,
                    deliveryTaskFactory,
                    callbackUrlCache,
                    null,
                    HorizonComponentId.UNSET,
                    null
            );
            DeliveryTask task = deliveryTaskFactory.createNew(record);

            // when
            task.run();

            // then
            verify(tracer, never()).startDebugSpan(anyString());
            verifyNoInteractions(restClient, cometMetrics, callbackUrlCache);
            verify(deliverySpan).error(hazelcastException);

            assertEquals(1.0, meterRegistry.get(METRIC_INTERNAL_EXCEPTION_COUNT)
                    .tag("exception_class", HazelcastInstanceNotActiveException.class.getName())
                    .counter()
                    .count());

            verify(deliveryResultListener).handleDeliveryResult(new DeliveryResult(
                    eventMessage,
                    Status.FAILED,
                    false,
                    hazelcastException,
                    deliverySpan,
                    HorizonComponentId.UNSET
            ));
        }
    }

    @Nested
    @DisplayName("Blank Callback URL")
    class BlankCallbackUrlTest {

        @Mock
        private DebugSpanWrapper callbackSpan;

        private SimpleMeterRegistry meterRegistry;

        @BeforeEach
        void setUpMetrics() {
            meterRegistry = new SimpleMeterRegistry();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("Finish callback span, record exception metric, and emit non-retryable FAILED when callback URL is blank")
        void shouldEmitFailedWithCallbackUrlNotFoundExceptionWhenCallbackUrlIsBlank(String blankCallbackUrl) {
            // given
            when(tracer.startSpan("delivery")).thenReturn(deliverySpan);
            when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(false);
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    blankCallbackUrl,
                    0L,
                    0,
                    deliveryResultListener,
                    deliveryTaskFactory,
                    callbackUrlCache,
                    null,
                    HorizonComponentId.UNSET,
                    null
            );
            DeliveryTask task = deliveryTaskFactory.createNew(record);

            // when
            task.run();

            // then
            verify(deliverySpan, never()).annotate("make callback request");
            verify(tracer, never()).startDebugSpan("callback");
            verify(tracer, never()).withDebugSpanInScope(callbackSpan);
            verify(tracer, never()).addTagsToSpanFromEventMessage(callbackSpan, eventMessage);
            verify(tracer, never()).addTagsToSpanFromSubscriptionEventMessage(callbackSpan, eventMessage);

            verifyNoInteractions(restClient, cometMetrics, callbackUrlCache, callbackSpan);

            var resultCaptor = ArgumentCaptor.forClass(DeliveryResult.class);
            verify(deliveryResultListener).handleDeliveryResult(resultCaptor.capture());

            DeliveryResult result = resultCaptor.getValue();
            assertEquals(eventMessage, result.subscriptionEventMessage());
            assertEquals(Status.FAILED, result.status());
            assertFalse(result.shouldRedeliver());
            assertEquals(deliverySpan, result.deliverySpan());
            assertEquals(HorizonComponentId.UNSET, result.messageSource());

            CallbackUrlNotFoundException exception = assertInstanceOf(CallbackUrlNotFoundException.class, result.exception());
            assertEquals(
                    String.format("No callback url found for EventMessage with id %s in cache and additional fields", eventMessage.getUuid()),
                    exception.getMessage()
            );

            verify(deliverySpan).error(exception);
            verify(deliverySpan, never()).finish();

            assertEquals(1.0, meterRegistry.get(METRIC_INTERNAL_EXCEPTION_COUNT)
                    .tag("exception_class", CallbackUrlNotFoundException.class.getName())
                    .counter()
                    .count());
            assertNull(meterRegistry.find(METRIC_CALLBACK_HTTP_CODE_COUNT).counter());
        }
    }

    private void setupRestClientWithResponseCallback() throws IOException, CouldNotFetchAccessTokenException {
        final var successfulStatusCode = 201;
        reset(restClient);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<FutureCallback<Message<HttpResponse,Void>>>getArgument(2).completed(new Message<>(new BasicHttpResponse(successfulStatusCode)));
            return null;
        }).when(restClient).callback(any(), anyString(), any());
        when(cometConfig.getSuccessfulStatusCodes()).thenReturn(List.of(successfulStatusCode));
    }
}