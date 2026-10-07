// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.cache;

import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheReadException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallbackUrlCacheTest {

    private final SubscriptionCacheReader subscriptionCache = mock(SubscriptionCacheReader.class);
    private final CallbackUrlCache callbackUrlCache = new CallbackUrlCache(subscriptionCache);

    @Test
    void shouldReadAndMapDeliveryTargetInformation() throws SubscriptionCacheReadException {
        var subscription = new Subscription();
        subscription.setCallback("https://example.test/callback");
        subscription.setDeliveryType("callback");
        subscription.setCircuitBreakerOptOut(true);
        subscription.setRetryableStatusCodes(List.of(429, 503));
        var spec = new SubscriptionResourceSpec();
        spec.setSubscription(subscription);
        var resource = new SubscriptionResource();
        resource.setSpec(spec);
        when(subscriptionCache.getById("subscription-id")).thenReturn(Optional.of(resource));

        var result = callbackUrlCache.getDeliveryTargetInformation("subscription-id");

        assertTrue(result.isPresent());
        assertEquals("https://example.test/callback", result.get().getUrl());
        assertEquals("callback", result.get().getDeliveryType());
        assertTrue(result.get().isOptOutCircuitBreaker());
        assertEquals(List.of(429, 503), result.get().getRetryableStatusCodes());
        verify(subscriptionCache).getById("subscription-id");
    }

    @Test
    void shouldReturnEmptyOnJsonCacheMappingError() throws SubscriptionCacheReadException {
        when(subscriptionCache.getById("subscription-id"))
                .thenThrow(new SubscriptionCacheReadException("mapping failed", new JsonCacheException("invalid json", null)));

        var result = callbackUrlCache.getDeliveryTargetInformation("subscription-id");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldThrowSubscriptionLookupExceptionWhenCacheReadFails() throws SubscriptionCacheReadException {
        var cause = new IllegalStateException("Hazelcast operation timed out");
        when(subscriptionCache.getById("subscription-id"))
                .thenThrow(new SubscriptionCacheReadException("read failed", cause));

        var exception = assertThrows(SubscriptionLookupException.class,
                () -> callbackUrlCache.getDeliveryTargetInformation("subscription-id"));

        assertSame(cause, exception.getCause().getCause());
    }

    @Test
    void shouldThrowSubscriptionLookupExceptionWhenCacheReturnsNoResult() throws SubscriptionCacheReadException {
        when(subscriptionCache.getById("subscription-id"))
                .thenThrow(new SubscriptionCacheReadException("Subscription cache returned no result container"));

        assertThrows(SubscriptionLookupException.class,
                () -> callbackUrlCache.getDeliveryTargetInformation("subscription-id"));
    }
}