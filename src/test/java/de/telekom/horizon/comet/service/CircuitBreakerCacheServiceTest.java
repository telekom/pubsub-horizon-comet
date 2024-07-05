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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        circuitBreakerCacheService.openCircuitBreaker(ObjectGenerator.TEST_SUBSCRIPTION_ID, "test", ObjectGenerator.TEST_ENVIRONMENT);

        verify(cacheService, times(1)).set(eq(ObjectGenerator.TEST_SUBSCRIPTION_ID), any());
    }

    @Test
    @DisplayName("Return true when CircuitBreaker is open")
    void returnTrueWhenCircuitBreakerOpen() throws JsonCacheException {
        var circuitBreakerMessage = new CircuitBreakerMessage(
                ObjectGenerator.TEST_SUBSCRIPTION_ID,
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
