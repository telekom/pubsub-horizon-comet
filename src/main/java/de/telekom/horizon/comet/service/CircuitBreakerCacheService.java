package de.telekom.horizon.comet.service;

import com.hazelcast.core.HazelcastInstanceNotActiveException;
import de.telekom.eni.pandora.horizon.cache.service.CacheService;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerMessage;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerStatus;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The {@code CircuitBreakerCacheService} class provides a service for interacting with a circuit breaker
 * cache using Hazelcast. It allows checking the status of a circuit breaker and opening a circuit breaker.
 * This class is intended to be used in scenarios where circuit breaker status needs to be managed and
 * updated in a distributed cache.
 *
 */

@Component
@AllArgsConstructor
public class CircuitBreakerCacheService {

    /**
     * The cache service used to store and retrieve circuit breaker status.
     */
    private final CacheService circuitBreakerCache;

    /**
     * Checks if the circuit breaker is open for the given subscription id.
     *
     * @param subscriptionId The subscriptionId for which to check the circuit breaker status.
     * @return true if the circuit breaker is open, false otherwise
     * @throws HazelcastInstanceNotActiveException if the hazelcast instance is not active
     */
    public boolean isCircuitBreakerOpenOrChecking(String subscriptionId) throws HazelcastInstanceNotActiveException {
        var result = circuitBreakerCache.get(subscriptionId);
        if (result.isPresent()) {
            CircuitBreakerMessage circuitBreakerMessage = (CircuitBreakerMessage) result.get();
            return CircuitBreakerStatus.OPEN.equals(circuitBreakerMessage.getStatus())
                    || CircuitBreakerStatus.CHECKING.equals(circuitBreakerMessage.getStatus());
        }
        return false;
    }

    /**
     * Opens the circuit breaker associated with the given subscriptionId, providing the callback URL
     * and environment information.
     *
     * @param subscriptionId The subscriptionId for which to open the circuit breaker.
     * @param callbackUrl    The callback URL to be associated with the circuit breaker.
     * @param environment    The environment for which to open the circuit breaker.
     * @throws HazelcastInstanceNotActiveException if the hazelcast instance is not active
     */
    public void openCircuitBreaker(String subscriptionId, String callbackUrl, String environment) throws HazelcastInstanceNotActiveException {
        var circuitBreakerMessage = new CircuitBreakerMessage(subscriptionId, CircuitBreakerStatus.OPEN, callbackUrl, environment);
        circuitBreakerCache.update(subscriptionId, circuitBreakerMessage);
    }
}
