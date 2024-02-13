package de.telekom.horizon.comet.config;

import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.victorialog.model.AdditionalFields;
import de.telekom.eni.pandora.horizon.victorialog.model.MetricNames;
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
                    .publishPercentileHistogram()
                    .register(this.meterRegistry));

            long trustedEventStartTimeMillis = (Long) subscriptionEventMessage.getAdditionalFields().get(AdditionalFields.START_TIME_TRUSTED.getValue());
            var duration = System.currentTimeMillis() - trustedEventStartTimeMillis;

            this.e2eTimers.get(timerKey).record(Duration.ofMillis(duration));

            subscriptionEventMessage.getAdditionalFields().put(metricName.getAsHeaderValue(), duration);
        }
    }
}
