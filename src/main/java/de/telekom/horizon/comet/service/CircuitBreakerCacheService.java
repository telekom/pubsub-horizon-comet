// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerMessage;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerStatus;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

/**
 * The {@code CircuitBreakerCacheService} class provides a service for interacting with a circuit breaker
 * cache using Hazelcast. It allows checking the status of a circuit breaker and opening a circuit breaker.
 * This class is intended to be used in scenarios where circuit breaker status needs to be managed and
 * updated in a distributed cache.
 *
 */

@Component
@AllArgsConstructor
@Slf4j
public class CircuitBreakerCacheService {

    /**
     * The cache service used to store and retrieve circuit breaker status.
     */
    private final JsonCacheService<CircuitBreakerMessage> circuitBreakerCache;

    /**
     * Checks if the circuit breaker is open for the given subscription id.
     *
     * @param subscriptionId The subscriptionId for which to check the circuit breaker status.
     * @return true if the circuit breaker is open, false otherwise
     * @throws HazelcastInstanceNotActiveException if the hazelcast instance is not active
     */
    public boolean isCircuitBreakerOpenOrChecking(String subscriptionId) throws HazelcastInstanceNotActiveException {
        try {
            var result = circuitBreakerCache.getByKey(subscriptionId);
            if (result.isPresent()) {
                CircuitBreakerMessage circuitBreakerMessage = result.get();
                return CircuitBreakerStatus.OPEN.equals(circuitBreakerMessage.getStatus());
            }
        } catch (JsonCacheException e) {
            log.error("Could not check status of circuit breaker for subscriptionId {}: {}", subscriptionId, e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            log.error("Exception checking CircuitBreakerCache for subscriptionId {}: {}", subscriptionId, e.getMessage());
        }
        return false;
    }

    /**
     * Opens the circuit breaker associated with the given subscriptionId, providing the callback URL
     * and environment information. If the circuit breaker message is already present, the republishing
     * count and the last opened is taken from the existing circuit breaker message.
     *
     * @param subscriptionId The subscriptionId for which to open the circuit breaker.
     * @param environment    The environment for which to open the circuit breaker.
     * @throws HazelcastInstanceNotActiveException if the hazelcast instance is not active
     */
    public void openCircuitBreaker(String subscriptionId, String eventType, String originMessageId, String environment) throws HazelcastInstanceNotActiveException {
        var newCircuitBreakerMessage = new CircuitBreakerMessage(
                subscriptionId,
                eventType,
                Date.from(Instant.now()),
                originMessageId,
                CircuitBreakerStatus.OPEN,
                environment,
                Date.from(Instant.now()),
                0
        );
        try {
            var result = circuitBreakerCache.getByKey(subscriptionId);

            if (result.isPresent()) {
                CircuitBreakerMessage existingCircuitBreakerMessage = result.get();
                newCircuitBreakerMessage.setLoopCounter(existingCircuitBreakerMessage.getLoopCounter());
                newCircuitBreakerMessage.setLastOpened(existingCircuitBreakerMessage.getLastOpened());
            }
            circuitBreakerCache.set(subscriptionId, newCircuitBreakerMessage);
        } catch (JsonCacheException e) {
            log.error("Could not open circuit breaker for subscriptionId {}: {}", subscriptionId, e.getMessage());
        } catch (Exception e) {
            log.error("Exception open CircuitBreakerCache for subscriptionId {}: {}", subscriptionId, e.getMessage());
        }

    }
}
