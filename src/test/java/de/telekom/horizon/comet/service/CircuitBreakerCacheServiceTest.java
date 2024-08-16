// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerMessage;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerStatus;
import de.telekom.horizon.comet.test.utils.ObjectGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerCacheServiceTest {
    @Mock
    JsonCacheService<CircuitBreakerMessage> cacheService;
    CircuitBreakerCacheService circuitBreakerCacheService;

    @BeforeEach
    void initMocks() {
        this.circuitBreakerCacheService = spy(new CircuitBreakerCacheService(cacheService));
    }

    @Test
    @DisplayName("Write CircuitBreakerMessage in Cache")
    void writeCircuitBreakerMessage() throws JsonCacheException {
        var circuitBreakerMessage = new CircuitBreakerMessage(
                ObjectGenerator.TEST_SUBSCRIPTION_ID,
                ObjectGenerator.TEST_EVENT_TYPE,
                Date.from(Instant.now()),
                "test",
                CircuitBreakerStatus.OPEN,
                ObjectGenerator.TEST_ENVIRONMENT,
                Date.from(Instant.now()),
                1
        );
        var testStartDate = Date.from(Instant.now().minusSeconds(1));
        when(cacheService.getByKey(eq(ObjectGenerator.TEST_SUBSCRIPTION_ID))).thenReturn(Optional.of(circuitBreakerMessage));

        circuitBreakerCacheService.openCircuitBreaker(ObjectGenerator.TEST_SUBSCRIPTION_ID, ObjectGenerator.TEST_EVENT_TYPE, "test", ObjectGenerator.TEST_ENVIRONMENT);

        verify(cacheService, times(1)).getByKey(eq(ObjectGenerator.TEST_SUBSCRIPTION_ID));

        ArgumentCaptor<CircuitBreakerMessage> captor = ArgumentCaptor.forClass(CircuitBreakerMessage.class);
        verify(cacheService, times(1)).set(eq(ObjectGenerator.TEST_SUBSCRIPTION_ID), captor.capture());

        CircuitBreakerMessage capturedMessage = captor.getValue();

        // Check if the loop counter is taken from the existing circuit breaker message
        assertEquals(circuitBreakerMessage.getLoopCounter(), capturedMessage.getLoopCounter());
       // Check if the last opened date is taken from the existing circuit breaker message
        long expectedTime = circuitBreakerMessage.getLastOpened().getTime();
        long actualTime = capturedMessage.getLastOpened().getTime();
        long tolerance = 1000; // 1000 milliseconds tolerance
        assertTrue(Math.abs(expectedTime - actualTime) <= tolerance, "Last opened date mismatch");
        // Check if last modified was updated
        assertTrue(capturedMessage.getLastModified().after(testStartDate));
    }

    @Test
    @DisplayName("Return true when CircuitBreaker is open")
    void returnTrueWhenCircuitBreakerOpen() throws JsonCacheException {
        var circuitBreakerMessage = new CircuitBreakerMessage(
                ObjectGenerator.TEST_SUBSCRIPTION_ID,
                ObjectGenerator.TEST_EVENT_TYPE,
                Date.from(Instant.now()),
                "test",
                CircuitBreakerStatus.OPEN,
                ObjectGenerator.TEST_ENVIRONMENT,
                null,
                0
        );
        when(cacheService.getByKey(eq(ObjectGenerator.TEST_SUBSCRIPTION_ID))).thenReturn(Optional.of(circuitBreakerMessage));

        assertTrue(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(ObjectGenerator.TEST_SUBSCRIPTION_ID));
        verify(cacheService, times(1)).getByKey(ObjectGenerator.TEST_SUBSCRIPTION_ID);
    }

    @Test
    @DisplayName("Return false when no CircuitBreaker is open")
    void returnFalseWhenNoCircuitBreakerOpen() throws JsonCacheException {
        when(cacheService.getByKey(eq(ObjectGenerator.TEST_SUBSCRIPTION_ID))).thenReturn(Optional.empty());

        assertFalse(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(ObjectGenerator.TEST_SUBSCRIPTION_ID));
        verify(cacheService, times(1)).getByKey(ObjectGenerator.TEST_SUBSCRIPTION_ID);
    }
}
