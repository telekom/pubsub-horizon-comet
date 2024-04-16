// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.health;

import de.telekom.horizon.comet.cache.CallbackUrlCache;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code SubscriberCacheHealthIndicator} is a Spring HealthIndicator implementation
 * responsible for monitoring the synchronization status of the subscriber cache.
 * This indicator is conditionally enabled based on the "kubernetes.enabled" property
 * being set to true.
 */
@Component
@ConditionalOnProperty(value = "kubernetes.enabled", havingValue = "true")
public class SubscriberCacheHealthIndicator implements HealthIndicator {

    private final CallbackUrlCache callbackUrlCache;

    /**
     * Constructs an instance of {@code SubscriberCacheHealthIndicator} with the
     * specified {@code InformerStoreInitHandler}.
     *
     * @param informerStoreInitHandler The InformerStoreInitHandler for managing
     *                                 subscriber cache synchronization.
     */
    public SubscriberCacheHealthIndicator(CallbackUrlCache callbackUrlCache) {
        this.callbackUrlCache = callbackUrlCache;
    }

    /**
     * Evaluates the health of the subscriber cache based on synchronization status.
     *
     * @return A {@code Health} object representing the current health status of
     *         the subscriber cache.
     */
    @Override
    public Health health() {
        Health.Builder status = Health.up();

        if (!callbackUrlCache.isHealthy()) {
            status = Health.down();
        }

        return status.build();
    }
}