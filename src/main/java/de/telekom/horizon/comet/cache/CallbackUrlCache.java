// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.cache;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code CallbackUrlCache} class provides a service for managing callback properties associated with
 * subscriptionIds. It uses an in-memory ConcurrentHashMap for efficient and thread-safe storage of callback properties.
 * This class is intended to be used in scenarios where callback information needs to be cached and quickly accessed.
 */
@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class CallbackUrlCache {

    /**
     * The ConcurrentHashMap to store subscriptionId to callback properties mapping.
     */
    private final Map<String, CallbackCacheProperties> cache = new ConcurrentHashMap<>();

    /**
     * Retrieves the callback properties associated with the given subscriptionId.
     *
     * @param subscriptionId The subscriptionId for which to retrieve callback properties.
     * @return The CallbackCacheProperties object associated with the subscriptionId, or null if not found.
     */
    public CallbackCacheProperties get(String subscriptionId) {
        return cache.get(subscriptionId);
    }

    /**
     * Adds the callback properties for the specified subscriptionId in the cache.
     *
     * @param subscriptionId          The subscriptionId for which to add callback properties.
     * @param callbackCacheProperties The CallbackCacheProperties object to be associated with the subscriptionId.
     */
    public void add(String subscriptionId, CallbackCacheProperties callbackCacheProperties) {
        cache.put(subscriptionId, callbackCacheProperties);
    }

    /**
     * Removes the callback properties associated with the given subscriptionId from the cache.
     *
     * @param subscriptionId The subscriptionId for which to remove callback properties.
     */
    public void remove(String subscriptionId) {
        cache.remove(subscriptionId);
    }
}