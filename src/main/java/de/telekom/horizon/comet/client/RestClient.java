// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.exception.CallbackException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code RestClient} class is responsible for sending subscription event messages
 * to the specified callbackUrl. The class manages OAuth2 token caching, HTTP client setup,
 * and event delivery to callbackUrls.
 */
@Component
@Slf4j
public class RestClient {

    private final OAuth2TokenCache oAuth2TokenCache;
    private final CometConfig cometConfig;
    private final HorizonTracer tracer;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    private final CallbackUrlCache callbackUrlCache;

    /**
     * Construct of a new {@code RestClient} with the specified params.
     *
     * @param cometConfig The CometConfig instance for retrieving configuration parameters.
     * @param tracer The HorizonTracer instance for retrieving tracing headers.
     * @param oAuth2TokenCache The OAuth2TokenCache instance for retrieving OAuth2 access tokens.
     * @param httpClient The CloseableHttpClient instance for making HTTP requests.
     * @param objectMapper The ObjectMapper instance for JSON serialization and deserialization.
     */
    @Autowired
    public RestClient(CometConfig cometConfig, HorizonTracer tracer, OAuth2TokenCache oAuth2TokenCache, CallbackUrlCache callbackUrlCache,CloseableHttpClient httpClient, ObjectMapper objectMapper, ApplicationContext context) throws InterruptedException {
        this.cometConfig = cometConfig;
        this.tracer = tracer;

        this.oAuth2TokenCache = oAuth2TokenCache;
        this.callbackUrlCache = callbackUrlCache;

        this.httpClient = httpClient;
        this.objectMapper = objectMapper;

        this.context = context;

        retrieveAllAccessTokens(context);

    }

    /**
     * Retrieves OAuth2 access token for the configured environments.
     *
     * @param context The ApplicationContext instance for closing the application if token retrieval fails.
     * @throws InterruptedException If the thread is interrupted while sleeping.
     */
    public void retrieveAllAccessTokens(ApplicationContext context) throws InterruptedException {
        var retryCount = 0;

        do {
            try {
                oAuth2TokenCache.retrieveAllAccessTokens();
                return;
            } catch (Exception e) {
                Thread.sleep(1000);
                retryCount++;

                // If token retrieval fails, the application will not be able to deliver events
                log.error("Error retrieving access tokens", e);
            }
        } while(retryCount < 5);

        // Close the application after 5 attempts to get the accessTokens
        SpringApplication.exit(context, () -> -2);
    }

    /**
     * Handles the callback for a subscription event message by sending it to
     * the specified callbackUrl.
     *
     * @param subscriptionEventMessage The subscription event message to be delivered.
     * @param callbackUrl              The callbackUrl to which the event should be sent.
     * @throws CallbackException       If there is an error during the callback process.
     * @throws IOException             If an IO error occurs while making the HTTP request.
     */
    public void callback(SubscriptionEventMessage subscriptionEventMessage, String callbackUrl, ApplicationContext context) throws CallbackException, IOException, CouldNotFetchAccessTokenException {
        var request = new HttpPost(callbackUrl);
        var event = subscriptionEventMessage.getEvent();

        addHeaderToRequest(request, subscriptionEventMessage.getHttpHeaders(), subscriptionEventMessage.getEnvironment());

        // throws JsonProcessingException -> IOException
        var payload = new StringEntity(objectMapper.writeValueAsString(event), StandardCharsets.UTF_8);
        request.setEntity(payload);

        // throws IOException
        executeRequest(callbackUrl, request);
    }

    /**
     * Executes the HTTP request and validates the response status code.
     *
     * @param callbackUrl The callbackUrl to which the request is made.
     * @param request     The HTTP request to be executed.
     * @throws IOException       If an IO error occurs during the HTTP request.
     * @throws CallbackException If the response status code is not acceptable.
     */
    private void executeRequest(String callbackUrl, HttpPost request) throws IOException, CallbackException {
        try (var response = httpClient.execute(request)) {
            // Compare response status code with acceptable status codes from config
            var statusCode = response.getStatusLine().getStatusCode();
            var successfulStatusCodes = cometConfig.getSuccessfulStatusCodes();

            if (!successfulStatusCodes.contains(statusCode)) {
                throw new CallbackException(String.format("Error while delivering event to callback '%s': %s", callbackUrl, response.getStatusLine().getReasonPhrase()), statusCode);
            }
        }
    }

    /**
     * Overrides a header in the HTTP request.
     *
     * @param request The HTTP request to be modified.
     * @param key     The header key to be overridden.
     * @param value   The new value for the header.
     */
    private void overrideHeader(HttpRequestBase request, String key, String value) {
        request.removeHeaders(key);
        request.setHeader(new BasicHeader(key, value));
    }

    /**
     * Adds headers to the HTTP request based on the provided map and environment.
     *
     * @param request     The HTTP request to which headers are added.
     * @param httpHeaders The map of headers to be added.
     * @param environment The environment for which headers are added.
     */
    private void addHeaderToRequest(HttpPost request, Map<String, List<String>> httpHeaders, String environment) throws CouldNotFetchAccessTokenException {
        if (httpHeaders != null) {
            httpHeaders.forEach((k, v) -> {
                if (cometConfig.getHeaderPropagationBlacklist().stream().noneMatch(k::matches)) {
                    v.forEach(e -> request.addHeader(k, e));
                }
            });
        }

        overrideHeader(request, HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        overrideHeader(request, HttpHeaders.AUTHORIZATION, "Bearer " + oAuth2TokenCache.getToken(Optional.ofNullable(environment).orElse("default")));
        overrideHeader(request, HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        overrideHeader(request, HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.displayName());

        tracer.getCurrentTracingHeaders().forEach((key, value) -> overrideHeader(request, key, value));
    }

    /**
     * Scheduled task method that triggers the retrieval of OAuth2 access tokens.
     * This method is scheduled to run at a fixed rate to ensure timely token updates.
     * In case of any issues during token retrieval, it sets an internal flag indicating
     * a problem and logs an error message.
     */
    @Scheduled(cron = "${comet.oidc.cronTokenFetch}")
    protected void triggerTokenRetrieval() {
        try {
            oAuth2TokenCache.retrieveAllAccessTokens();
        } catch (CouldNotFetchAccessTokenException e) {
            log.error("Error retrieving scheduled access tokens", e);
            SpringApplication.exit(context, () -> -2);
        }
    }
}
