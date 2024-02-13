package de.telekom.horizon.comet.config.rest;

import de.telekom.horizon.comet.config.CometConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration class for creating a configured {@link RestTemplate}.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * The CometConfig instance for retrieving configuration parameters.
     */
    private final CometConfig cometConfig;

    /**
     * Construct of a new {@code RestTemplateConfig} with the specified CometConfig.
     * @param cometConfig The CometConfig instance for retrieving configuration parameters.
     */
    @Autowired
    public RestTemplateConfig(CometConfig cometConfig) {
        this.cometConfig = cometConfig;
    }

    /**
     * Creates and returns a {@link RestTemplate} bean with specified connection and read timeouts.
     *
     * @return A configured {@link RestTemplate} bean.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(getClientHttpRequestFactory());
    }

    /**
     * Retrieves a SimpleClientHttpRequestFactory with specified connection and read timeouts.
     *
     * @return A SimpleClientHttpRequestFactory configured with a connection timeout and a read timeout.
     */
    private SimpleClientHttpRequestFactory getClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory clientHttpRequestFactory  = new SimpleClientHttpRequestFactory();

        clientHttpRequestFactory.setConnectTimeout(cometConfig.getRetrieveTokenConnectTimeout());
        clientHttpRequestFactory.setReadTimeout(cometConfig.getRetrieveTokenReadTimeout());

        return clientHttpRequestFactory;
    }
}