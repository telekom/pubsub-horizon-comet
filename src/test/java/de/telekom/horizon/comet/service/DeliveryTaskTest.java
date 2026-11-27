// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.DebugSpanWrapper;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.client.RestClient;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.config.CometMetrics;
import de.telekom.horizon.comet.exception.CallbackUrlNotFoundException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import de.telekom.horizon.comet.test.utils.ObjectGenerator;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
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
    private DeliveryService deliveryService;
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
                deliveryService,
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

        verify(deliveryService).updateDeliveryStateToWaiting(deliverySpan, eventMessage);
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
                deliveryService,
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
        verify(deliveryService).updateDeliveryStateToWaiting(deliverySpan, eventMessage);
    }

    @Nested
    @DisplayName("CircuitBreaker is NOT OPEN")
    class CircuitBreakerNotOpenTest {

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
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    CALLBACK_URL,
                    0L,
                    0,
                    deliveryService,
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

            verify(restClient).callback(eq(eventMessage), eq(CALLBACK_URL), any());

            assertEquals(1.0, meterRegistry.get(METRIC_CALLBACK_HTTP_CODE_COUNT)
                    .tag(TAG_HTTP_CODE, String.valueOf(HttpStatus.CREATED.value()))
                    .counter()
                    .count());
            assertNull(meterRegistry.find(METRIC_INTERNAL_EXCEPTION_COUNT).counter());
            verify(cometMetrics).recordE2eEventLatencyAndExtendMetadata(
                    eventMessage, MetricNames.EndToEndLatencyCustomer, HorizonComponentId.UNSET
            );
            verify(deliveryService).updateDeliveryStateToDelivered(deliverySpan, eventMessage);
        }

        @Test
        @DisplayName("Reuse span, annotate backoff, execute callback, and emit DELIVERED on redelivery when CircuitBreaker is closed")
        void shouldReuseSpanAndExecuteCallbackOnRedeliveryWhenCircuitBreakerClosed() throws Exception {
            // given
            long backoffInterval = 1500L;
            int retryCount = 2;
            when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(false);
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    CALLBACK_URL,
                    backoffInterval,
                    retryCount,
                    deliveryService,
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
            verify(deliveryService).updateDeliveryStateToDelivered(deliverySpan, eventMessage);
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
                    deliveryService,
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

            verify(deliveryService).updateDeliveryStateToFailed(deliverySpan, eventMessage);
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
                    deliveryService,
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

            verifyNoInteractions(restClient, cometMetrics, callbackSpan);

            verify(deliveryService).updateDeliveryStateToFailed(deliverySpan, eventMessage);
            verify(callbackUrlCache).getDeliveryTargetInformation(TEST_SUBSCRIPTION_ID);

            verify(deliverySpan).error(any(CallbackUrlNotFoundException.class));
            verify(deliverySpan, never()).finish();

            assertEquals(1.0, meterRegistry.get(METRIC_INTERNAL_EXCEPTION_COUNT)
                    .tag("exception_class", CallbackUrlNotFoundException.class.getName())
                    .counter()
                    .count());
            assertNull(meterRegistry.find(METRIC_CALLBACK_HTTP_CODE_COUNT).counter());
        }
    }

    @Nested
    @DisplayName("Branch 4.4: Invalid Callback URI Syntax")
    class InvalidCallbackUriTest {

        @Mock
        private DebugSpanWrapper callbackSpan;
        @Mock
        private OAuth2TokenCache oAuth2TokenCache;
        @Mock
        private CloseableHttpAsyncClient httpClient;
        @Mock
        private ObjectMapper objectMapper;
        @Mock
        private ApplicationContext applicationContext;

        private SimpleMeterRegistry meterRegistry;
        private DeliveryTaskFactory realRestClientTaskFactory;

        @BeforeEach
        void setUpRealRestClient() throws InterruptedException {
            meterRegistry = new SimpleMeterRegistry();
            RestClient realRestClient = new RestClient(
                    cometConfig,
                    tracer,
                    oAuth2TokenCache,
                    httpClient,
                    objectMapper,
                    applicationContext
            );
            clearInvocations(oAuth2TokenCache);

            realRestClientTaskFactory = new DeliveryTaskFactory(
                    cometConfig,
                    realRestClient,
                    stateService,
                    circuitBreakerCacheService,
                    deDuplicationService,
                    tracer,
                    metricsHelper,
                    cometMetrics,
                    executor
            );
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://invalid url", "://missing-scheme"})
        @DisplayName("Finish callback span, record IllegalArgumentException metric, and emit non-retryable FAILED on invalid URI syntax")
        void shouldEmitFailedWithIllegalArgumentExceptionWhenCallbackUrlHasInvalidUriSyntax(String invalidCallbackUrl) throws JsonProcessingException {
            // given
            when(tracer.startSpan("delivery")).thenReturn(deliverySpan);
            when(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(TEST_SUBSCRIPTION_ID)).thenReturn(false);
            when(metricsHelper.buildTagsFromSubscriptionEventMessage(eventMessage)).thenReturn(Tags.empty());
            when(metricsHelper.getRegistry()).thenReturn(meterRegistry);

            var record = new DeliveryTaskRecord(
                    eventMessage,
                    invalidCallbackUrl,
                    0L,
                    0,
                    deliveryService,
                    realRestClientTaskFactory,
                    callbackUrlCache,
                    null,
                    HorizonComponentId.UNSET,
                    null
            );
            DeliveryTask task = realRestClientTaskFactory.createNew(record);

            // when
            task.run();

            // then
            verify(deliverySpan).annotate("make callback request");

            verifyNoInteractions(oAuth2TokenCache, httpClient, cometMetrics, callbackUrlCache, callbackSpan);

            verify(objectMapper).writeValueAsBytes(any());
            verify(deliveryService).updateDeliveryStateToFailed(deliverySpan, eventMessage);

            verify(deliverySpan).error(any(IllegalArgumentException.class));
            verify(deliverySpan, never()).finish();

            assertEquals(1.0, meterRegistry.get(METRIC_INTERNAL_EXCEPTION_COUNT)
                    .tag("exception_class", IllegalArgumentException.class.getName())
                    .counter()
                    .count());
            assertNull(meterRegistry.find(METRIC_CALLBACK_HTTP_CODE_COUNT).counter());
        }
    }

    private void setupRestClientWithResponseCallback() throws IOException, CouldNotFetchAccessTokenException {
        final var successfulStatusCode = 201;
        reset(restClient, executor);
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