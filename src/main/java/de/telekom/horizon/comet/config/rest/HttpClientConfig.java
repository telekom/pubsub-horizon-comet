// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config.rest;

import de.telekom.horizon.comet.config.CometConfig;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * The {@code HttpClientConfig} class for creating and configuring the components related to HTTP client functionality.
 */
@Configuration
public class HttpClientConfig {

    /**
     * The CometConfig instance for retrieving configuration parameters.
     */
    private final CometConfig cometConfig;

    /**
     * Construct of a new {@code HttpClientConfig} with the specified CometConfig.
     * @param cometConfig The CometConfig instance for retrieving configuration parameters.
     */
    @Autowired
    public HttpClientConfig(CometConfig cometConfig) {
        this.cometConfig = cometConfig;
    }

    /**
     * Creates and configures the {@code RequestConfig} bean for request timeout settings.
     *
     * @return The configured {@code RequestConfig} bean.
     */
    @Bean
    public RequestConfig requestConfig() {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                .build();
    }

    /**
     * Creates and configures the {@code PoolingAsyncClientConnectionManager} bean used for managing HTTP connections.
     *
     * @return The configured {@code PoolingAsyncClientConnectionManager} bean.
     */
    @Bean
    public PoolingAsyncClientConnectionManager poolingAsyncClientConnectionManager() {
        return PoolingAsyncClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                        //.setHandshakeTimeout(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                        .build())
                .setMessageMultiplexing(true)
                .setMaxConnTotal(cometConfig.getMaxConnections())
                //todo: with h2 we might as well want to go with the default 5 here, due to streams
                //.setMaxConnPerRoute(cometConfig.getMaxConnections())
                .build();
    }

    @Bean
    public IOReactorConfig ioReactorConfig() {
        return IOReactorConfig.custom()
                .build();
    }

    @Bean
    public CloseableHttpAsyncClient httpAsyncClient(
            PoolingAsyncClientConnectionManager poolingAsyncClientConnectionManager,
            RequestConfig requestConfig,
            IOReactorConfig ioReactorConfig) {
        final var client = HttpAsyncClients.custom()
                .setConnectionManager(poolingAsyncClientConnectionManager)
                .setIOReactorConfig(ioReactorConfig)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                //.setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                .disableCookieManagement()
                .disableAutomaticRetries()
                .build();
        client.start();

        return client;
    }
}
