// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config.rest;

import de.telekom.horizon.comet.config.CometConfig;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
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
                .setConnectionRequestTimeout((int) cometConfig.getMaxTimeout())
                .setConnectTimeout((int) cometConfig.getMaxTimeout())
                .setSocketTimeout((int) cometConfig.getMaxTimeout())
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
                .build();
    }
}
