package de.telekom.horizon.comet.health;

import de.telekom.eni.pandora.horizon.kubernetes.InformerStoreInitHandler;
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

    private final InformerStoreInitHandler informerStoreInitHandler;

    /**
     * Constructs an instance of {@code SubscriberCacheHealthIndicator} with the
     * specified {@code InformerStoreInitHandler}.
     *
     * @param informerStoreInitHandler The InformerStoreInitHandler for managing
     *                                 subscriber cache synchronization.
     */
    public SubscriberCacheHealthIndicator(InformerStoreInitHandler informerStoreInitHandler) {
        this.informerStoreInitHandler = informerStoreInitHandler;
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

        if (!informerStoreInitHandler.isFullySynced()) {
            status = Health.down();
        }

        return status.withDetails(informerStoreInitHandler.getInitalSyncedStats()).build();
    }
}