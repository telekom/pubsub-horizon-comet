// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config;

import de.telekom.eni.pandora.horizon.metrics.AdditionalFields;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code CometMetrics} class is responsible for recording metrics for Comet.
 */
@Component
public class CometMetrics {

    private final MeterRegistry meterRegistry;

    private final Map<String, Timer> e2eTimers;

    /**
     * Constructor for CometMetrics.
     *
     * @param meterRegistry The Micrometer registry for recording metrics.
     */
    public CometMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.e2eTimers = new HashMap<>();

    }

    /**
     * Records the latency of an event message and extends the metadata based on the provided
     * SubscriptionEventMessage, MetricNames, and HorizonComponentId.
     *
     * @param subscriptionEventMessage The event message to record the latency for.
     * @param metricName               The name of the metric to record.
     * @param messageSource            The source of the message.
     */
    public void recordE2eEventLatencyAndExtendMetadata(SubscriptionEventMessage subscriptionEventMessage, MetricNames metricName, HorizonComponentId messageSource) {
        if (subscriptionEventMessage.getAdditionalFields() != null &&
                subscriptionEventMessage.getAdditionalFields().containsKey(AdditionalFields.START_TIME_TRUSTED.getValue())) {

            var timerKey = String.format("%s-%s-%s-%s",subscriptionEventMessage.getEvent().getType(), metricName.getValue(), messageSource.getClientId(), subscriptionEventMessage.getEnvironment());

            this.e2eTimers.putIfAbsent(timerKey, Timer.builder(metricName.getValue())
                    .tag("environment", subscriptionEventMessage.getEnvironment())
                    .tag("clientId", messageSource.getClientId())
                    .minimumExpectedValue(Duration.ofMillis(10))
                    .maximumExpectedValue(Duration.ofHours(4))
                    .serviceLevelObjectives(
                            Duration.ofMillis(50),
                            Duration.ofMillis(100),
                            Duration.ofMillis(250),
                            Duration.ofMillis(500),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(2500),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(30),
                            Duration.ofMinutes(1),
                            Duration.ofMinutes(5),
                            Duration.ofMinutes(30))
                    .register(this.meterRegistry));

            long trustedEventStartTimeMillis = (Long) subscriptionEventMessage.getAdditionalFields().get(AdditionalFields.START_TIME_TRUSTED.getValue());
            var duration = System.currentTimeMillis() - trustedEventStartTimeMillis;

            this.e2eTimers.get(timerKey).record(Duration.ofMillis(duration));

            subscriptionEventMessage.getAdditionalFields().put(metricName.getAsHeaderValue(), duration);
        }
    }
}
