// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.config.rest;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code AuthConfig} class represents the configuration properties for OAuth2 authentication.
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "comet.oidc")
public class AuthProperties {

    String tokenUri;
    String clientId;
    String clientSecret;

}