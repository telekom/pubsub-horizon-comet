// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.horizon.comet.exception.BadTokenResponseException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.exception.TokenRequestErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code OAuth2TokenCache} class is responsible for caching and managing OAuth2 access tokens.
 * The class supports multiple environments with different client secrets.
 */
@Slf4j
public class OAuth2TokenCache {
    public static final String IRIS_REALM_PLACEHOLDER = "<realm>";
    public static final String DEFAULT_REALM = "default";
    private static final String GRANT_TYPE_FIELD = "grant_type";
    private static final String GRANT_TYPE = "client_credentials";
    private static final String CLIENT_ID_FIELD = "client_id";
    private static final String CLIENT_SECRET_FIELD = "client_secret";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String accessTokenUrl;
    private final String clientId;
    private final Map<String, String> clientSecretMap = new HashMap<>();
    private final Map<String, AccessToken> accessTokenMap = new HashMap<>();
    private Boolean internalIssueDetected = false;

    /**
     * Constructs a new {@code OAuth2TokenCache} instance with the specified parameters.
     *
     * @param accessTokenUrl The URL of the OAuth 2.0 token endpoint.
     * @param clientId       The clientID used for authentication.
     * @param clientSecret   The client secret used for authentication. Can contain multiple secrets separated by commas in the form of "key1=value1,key2=value2".
     * @param objectMapper   The object mapper used to parse the response from the token endpoint.
     * @param restTemplate   The RestTemplate used for making HTTP requests to the token endpoint.
     *                       It should be configured with appropriate settings like connection
     *                       timeouts, proxy settings, etc.
     */
    public OAuth2TokenCache(String accessTokenUrl, String clientId, String clientSecret, ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.accessTokenUrl = accessTokenUrl;
        this.clientId = clientId;

        Arrays.stream(clientSecret.split(",")).
                forEach(s -> this.clientSecretMap.put(s.split("=")[0], s.split("=")[1]));
    }

    /**
     * Retrieves the OAuth2 access token for the specified environment.
     *
     * @param environment The environment for which to retrieve the access token.
     * @return The OAuth2 access token.
     */
    public synchronized String getToken(String environment) throws CouldNotFetchAccessTokenException {
        if (!clientSecretMap.containsKey(environment)){
            environment = DEFAULT_REALM;
        }
        if (isNotValidToken(environment)) {
            retrieveAccessToken(environment);
        }
        return accessTokenMap.get(environment).getToken();
    }

    /**
     * Checks if the access token for the specified environment is not valid or expired.
     *
     * @param environment The environment to check.
     * @return True if the token is not valid or expired, false otherwise.
     */
    public boolean isNotValidToken(String environment) {
        return accessTokenMap.get(environment) == null || accessTokenMap.get(environment).isExpired();
    }

    /**
     * Retrieves OAuth2 access tokens for all configured environments.
     * This method iterates through the configured environments and retrieves
     * the corresponding access tokens. If any issues occur during token retrieval,
     * it sets an internal flag indicating a problem.
     *
     * @throws CouldNotFetchAccessTokenException If an error occurs while fetching any of the access tokens.
     */
    public void retrieveAllAccessTokens() throws CouldNotFetchAccessTokenException {
        for(var environment : clientSecretMap.keySet()) {
            retrieveAccessToken(environment);
        }

        setInternalIssueDetected(false);
    }

    /**
     * Retrieves the OAuth2 access token for the specified environment and caches it.
     * This method performs the retrieval by exchanging client credentials for an access token
     * using the OAuth2 protocol. The retrieved token is then cached for future use.
     *
     * @param environment The environment for which to retrieve the access token.
     * @throws CouldNotFetchAccessTokenException If an error occurs during access token retrieval.
     */
    public void retrieveAccessToken(String environment) throws CouldNotFetchAccessTokenException {
        var secret = clientSecretMap.get(environment);
        var exchangeUrl = accessTokenUrl.replace(IRIS_REALM_PLACEHOLDER, environment);
        log.info("Trying to retrieve oidc token from {} for realm {}", exchangeUrl, environment);

        final ResponseEntity<String> response;
        try {
             response = restTemplate.exchange(exchangeUrl, HttpMethod.POST, createRequest(secret), String.class);
        } catch (RestClientException e) {
            log.error("Error retrieving access tokens", e);
            throw new CouldNotFetchAccessTokenException(e);
        }

        accessTokenMap.put(environment, convertResponseToAccessToken(response));
    }

    /**
     * Creates the HTTP request entity for obtaining an OAuth2 access token.
     *
     * @param secret The client secret for authentication.
     * @return The HTTP request entity.
     */
    private HttpEntity<MultiValueMap<String, String>> createRequest(String secret) {
        HttpHeaders headers = createHeader();
        MultiValueMap<String, String> body = createBody(secret);

        return new HttpEntity<>(body, headers);
    }

    /**
     * Converts the HTTP response from the token endpoint into an OAuth2 access token.
     *
     * @param response The HTTP response from the token endpoint.
     * @return The OAuth2 access token.
     * @throws TokenRequestErrorException if an error occurs during token retrieval.
     */
    private AccessToken convertResponseToAccessToken(final ResponseEntity<String> response) {
        final HttpStatusCode statusCode = response.getStatusCode();
        final String responseAsJson = response.getBody();

        if (statusCode.is2xxSuccessful()) {
            log.info("Successfully retrieved oidc token");
            final Map<String, Object> responseAsMap = parseResponse(responseAsJson);
            return AccessToken.of(responseAsMap);
        } else {
            log.warn("Error occurred while requesting oidc token: {}", response);
            throw TokenRequestErrorException.of(response.toString());
        }
    }

    /**
     * Creates the HTTP headers for the token request.
     *
     * @return The HTTP headers.
     */
    private HttpHeaders createHeader() {
        final var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(accessTokenUrl);
        return headers;
    }

    /**
     * Creates the body for the token request.
     *
     * @param secret The client secret.
     * @return The body as a MultiValueMap.
     */
    private MultiValueMap<String, String> createBody(String secret) {
        final MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(GRANT_TYPE_FIELD, GRANT_TYPE);
        body.add(CLIENT_ID_FIELD, clientId);
        body.add(CLIENT_SECRET_FIELD, secret);
        return body;
    }

    /**
     * Parses the response from the token endpoint into a map.
     *
     * @param responseAsJson The response from the token endpoint as JSON.
     * @return The response as a map.
     * @throws BadTokenResponseException if the response cannot be parsed.
     */
    private Map<String, Object> parseResponse(final String responseAsJson) {
        try {
            return objectMapper.readValue(responseAsJson, Map.class);
        } catch (IOException e) {
            throw BadTokenResponseException.of(responseAsJson, e);
        }
    }

    /**
     * Checks if the OAuth2 access token cache is valid.
     * The cache is considered invalid if an internal issue is detected or if any
     * access token in the cache is not valid.
     *
     * @return true if the access token cache is valid, false otherwise.
     */
    public boolean isAccessTokenCacheValid() {
        if(Boolean.TRUE.equals(internalIssueDetected)) {
            return false;
        }

        for (var environment : accessTokenMap.keySet()) {
            if (isNotValidToken(environment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets the internal flag indicating an issue with the access token cache.
     *
     * @param hasIssue true if there is an issue with the cache, false otherwise.
     */
    public void setInternalIssueDetected(boolean hasIssue) {
        this.internalIssueDetected  = hasIssue;

    }
}
