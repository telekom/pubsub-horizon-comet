// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.cache;

import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code CallbackUrlCache} class provides a service for managing callback properties associated with
 * subscriptionIds. It uses an in-memory ConcurrentHashMap for efficient and thread-safe storage of callback properties.
 * This class is intended to be used in scenarios where callback information needs to be cached and quickly accessed.
 */
@Service
@Slf4j
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class CallbackUrlCache {

    private final JsonCacheService<SubscriptionResource> subscriptionCache;

    public CallbackUrlCache(JsonCacheService<SubscriptionResource> subscriptionCache) {
        this.subscriptionCache = subscriptionCache;
    }

    /**
     * Retrieves the callback properties associated with the given subscriptionId.
     *
     * @param subscriptionId The subscriptionId for which to retrieve callback properties.
     * @return The DeliveryTargetInformation object associated with the subscriptionId, or null if not found.
     */

    public Optional<DeliveryTargetInformation> getDeliveryTargetInformation(String subscriptionId) {

        Optional<SubscriptionResource> subscription = Optional.empty();

        try {
            subscription = subscriptionCache.getByKey(subscriptionId);
        } catch (JsonCacheException e) {
            log.error("Error occurred while executing query on JsonCacheServe", e);

        }

        return subscription.map(subscriptionResource -> new DeliveryTargetInformation
                (subscriptionResource.getSpec().getSubscription().getCallback(), subscriptionResource.getSpec().getSubscription().isCircuitBreakerOptOut()));

    }
}