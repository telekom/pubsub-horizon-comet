// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import brave.Span;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.client.RestClient;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.config.CometMetrics;
import de.telekom.horizon.comet.model.DeliveryResult;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import de.telekom.horizon.comet.test.utils.ObjectGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static de.telekom.horizon.comet.test.utils.ObjectGenerator.TEST_SUBSCRIPTION_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryTaskTest {

    private static final String CALLBACK_URL = "https://example.com/callback";

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
}