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
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
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
     * Creates and configures the {@code PoolingHttpClientConnectionManager} bean used for managing HTTP connections.
     *
     * @return The configured {@code PoolingHttpClientConnectionManager} bean.
     */
    @Bean
    public PoolingHttpClientConnectionManager poolingHttpClientConnectionManager() {
        var connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(cometConfig.getMaxConnections());
        connectionManager.setDefaultMaxPerRoute(cometConfig.getMaxConnections());
        return connectionManager;
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


    public PoolingAsyncClientConnectionManager poolingAsyncClientConnectionManager() {
        return PoolingAsyncClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                        .setHandshakeTimeout(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                        .build())
                .setMaxConnTotal(cometConfig.getMaxConnections())
                //todo: some other sensible value is needed
                .setMaxConnPerRoute(cometConfig.getMaxConnections())
                .build();
    }


    public IOReactorConfig ioReactorConfig() {
        return IOReactorConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(cometConfig.getMaxTimeout()))
                .build();
    }


    public CloseableHttpAsyncClient httpAsyncClient(
            PoolingAsyncClientConnectionManager poolingAsyncClientConnectionManager,
            RequestConfig requestConfig,
            IOReactorConfig ioReactorConfig) {
        return HttpAsyncClients.custom()
                .setConnectionManager(poolingAsyncClientConnectionManager)
                .setIOReactorConfig(ioReactorConfig)
                .setDefaultRequestConfig(requestConfig)
                .disableCookieManagement()
                .build();
    }

    /**
     * Creates and configures the {@code CloseableHttpClient} bean using the provided connection manager and request config.
     *
     * @param poolingHttpClientConnectionManager The PoolingHttpClientConnectionManager bean.
     * @param requestConfig                      The RequestConfig bean.
     * @return The configured CloseableHttpClient bean.
     */
    @Bean
    public CloseableHttpClient httpClient(PoolingHttpClientConnectionManager poolingHttpClientConnectionManager, RequestConfig requestConfig) {
        return HttpClientBuilder
                .create()
                .setConnectionManager(poolingHttpClientConnectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableCookieManagement()
                .disableAutomaticRetries()
                .build();
    }
}
