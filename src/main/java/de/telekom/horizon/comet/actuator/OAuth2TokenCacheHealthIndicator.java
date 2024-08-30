// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.actuator;

import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code OAuth2TokenCacheHealthIndicator} is a Spring HealthIndicator implementation
 * that checks the health of OAuth2 tokens stored in an {@code OAuth2TokenCache}.
 * It iterates through the token cache for different environments and determines
 * whether the stored tokens are valid or not.
 */
@Component
public class OAuth2TokenCacheHealthIndicator implements HealthIndicator {

    private final OAuth2TokenCache oAuth2TokenCache;

    /**
     * Constructs an instance of {@code OAuth2TokenCacheHealthIndicator} with the
     * specified {@code OAuth2TokenCache}.
     *
     * @param oAuth2TokenCache The OAuth2 token cache to monitor.
     */
    public OAuth2TokenCacheHealthIndicator(OAuth2TokenCache oAuth2TokenCache) {
        this.oAuth2TokenCache = oAuth2TokenCache;
    }

    /**
     * Checks the health of the OAuth2 tokens in the cache for different environments.
     * The health status is determined based on the validity of the stored tokens.
     *
     * @return The health status indicating whether the OAuth2 tokens are valid or not.
     */
    @Override
    public Health health() {
        Health.Builder status = Health.up();

        if(!oAuth2TokenCache.isAccessTokenCacheValid()) {
            status = Health.down();
        }

        return status.build();
    }
}
