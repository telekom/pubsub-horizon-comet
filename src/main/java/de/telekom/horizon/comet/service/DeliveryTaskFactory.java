// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.client.RestClient;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.config.CometMetrics;
import de.telekom.horizon.comet.model.DeliveryTaskRecord;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * The {@code DeliveryTaskFactory} class for creating instances of {@link DeliveryTask}.
 * This factory is responsible for creating new delivery tasks with the provided dependencies.
 */
@Getter
@Component
public class DeliveryTaskFactory {

    private final CometConfig cometConfig;

    private final RestClient restClient;

    private final StateService stateService;

    private final CircuitBreakerCacheService circuitBreakerCacheService;

    private final DeDuplicationService deDuplicationService;

    private final HorizonTracer tracer;

    private final HorizonMetricsHelper metricsHelper;

    private final CometMetrics cometMetrics;

    /**
     * Constructs a DeliveryTaskFactory with necessary dependencies.
     *
     * @param cometConfig               The CometConfig instance for configuration.
     * @param restClient                The RestClient instance for making HTTP requests.
     * @param stateService              The StateService instance for managing event states.
     * @param circuitBreakerCacheService The CircuitBreakerCacheService instance for managing circuit breaker states.
     * @param deDuplicationService      The DeDuplicationService instance for handling duplicate events.
     * @param tracer                    The HorizonTracer instance for creating spans.
     * @param metricsHelper             The HorizonMetricsHelper instance for metrics-related operations.
     * @param cometMetrics              The CometMetrics instance for recording metrics.
     */
    public DeliveryTaskFactory(CometConfig cometConfig, RestClient restClient, StateService stateService, CircuitBreakerCacheService circuitBreakerCacheService, DeDuplicationService deDuplicationService, HorizonTracer tracer, HorizonMetricsHelper metricsHelper, CometMetrics cometMetrics) {
        this.cometConfig = cometConfig;
        this.restClient = restClient;
        this.stateService = stateService;
        this.circuitBreakerCacheService = circuitBreakerCacheService;
        this.deDuplicationService = deDuplicationService;
        this.tracer = tracer;
        this.metricsHelper = metricsHelper;
        this.cometMetrics = cometMetrics;
    }

    /**
     * Creates a new instance of {@link DeliveryTask} with the provided {@link DeliveryTaskRecord}.
     *
     * @param deliveryTaskRecord The DeliveryTaskRecord containing information for creating the new task.
     * @return A new instance of DeliveryTask.
     */
    public DeliveryTask createNew(DeliveryTaskRecord deliveryTaskRecord) {
        return new DeliveryTask(deliveryTaskRecord);
    }
}
