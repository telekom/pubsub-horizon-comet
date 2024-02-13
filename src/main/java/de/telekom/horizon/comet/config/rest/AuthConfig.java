// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * The {@code AuthConfig} class represents the configuration properties for OAuth2 authentication.
 */
@Slf4j
@Setter
@Configuration
@ConfigurationProperties(prefix = "comet.oidc")
public class AuthConfig {

    String tokenUri;
    String clientId;
    String clientSecret;

    /**
     * Creates and configures the {@code OAuth2TokenCache} bean used for caching OAuth2 tokens.
     *
     * @param objectMapper The ObjectMapper to be used for JSON serialization and deserialization.
     * @return The configured {@code OAuth2TokenCache} bean.
     */
    @Bean
    public OAuth2TokenCache customOauth2Filter(ObjectMapper objectMapper, RestTemplate restTemplate) {
        return new OAuth2TokenCache(tokenUri, clientId, clientSecret, objectMapper, restTemplate);
    }

}