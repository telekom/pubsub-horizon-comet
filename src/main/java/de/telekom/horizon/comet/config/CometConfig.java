// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@Getter
public class CometConfig {

    @Value("#{'${comet.callback.redelivery-status-codes}'.split(',')}")
    private List<Integer> redeliveryStatusCodes;

    @Value("#{'${comet.callback.successful-status-codes}'.split(',')}")
    private List<Integer> successfulStatusCodes;

    @Value("#{'${comet.security.headerPropagationBlacklist}'.split(',')}")
    private List<String> headerPropagationBlacklist;

    @Value("${comet.security.retrieve-token-connect-timeout:5000}")
    private int retrieveTokenConnectTimeout;

    @Value("${comet.security.retrieve-token-read-timeout:5000}")
    private int retrieveTokenReadTimeout;

    @Value("${comet.callback.max-retries}")
    private int maxRetries;

    @Value("${comet.callback.initial-backoff-interval-ms}")
    private int initialBackoffIntervalInMs;

    @Value("${comet.callback.max-backoff-interval-ms}")
    private int maxBackoffIntervalInMs;

    @Value("${comet.callback.backoff-multiplier}")
    private double backoffMultiplier;

    @Value("${comet.callback.max-timeout}")
    private long maxTimeout;

    @Value("${comet.callback.max-connections}")
    private int maxConnections;

    @Value("${horizon.kafka.consumerThreadpoolSize}")
    private int consumerThreadPoolSize;

    @Value("${horizon.kafka.consumerQueueCapacity}")
    private int consumerQueueCapacity;

    @Value("${comet.callback.redelivery-threadpool-size}")
    private int redeliveryThreadPoolSize;

    @Value("${comet.callback.redelivery-queue-capacity}")
    private int redeliveryQueueCapacity;
}
