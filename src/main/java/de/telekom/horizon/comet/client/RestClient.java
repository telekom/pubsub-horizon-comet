// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.exception.CallbackException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityProducer;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncRequestBuilder;
import org.apache.hc.core5.http.nio.support.BasicResponseConsumer;
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
import java.util.concurrent.ExecutionException;

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
    private final CloseableHttpAsyncClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    /**
     * Construct of a new {@code RestClient} with the specified params.
     *
     * @param cometConfig      The CometConfig instance for retrieving configuration parameters.
     * @param tracer           The HorizonTracer instance for retrieving tracing headers.
     * @param oAuth2TokenCache The OAuth2TokenCache instance for retrieving OAuth2 access tokens.
     * @param httpClient       The CloseableHttpClient instance for making HTTP requests.
     * @param objectMapper     The ObjectMapper instance for JSON serialization and deserialization.
     */
    @Autowired
    public RestClient(CometConfig cometConfig, HorizonTracer tracer, OAuth2TokenCache oAuth2TokenCache, CloseableHttpAsyncClient httpClient, ObjectMapper objectMapper, ApplicationContext context) throws InterruptedException {
        this.cometConfig = cometConfig;
        this.tracer = tracer;

        this.oAuth2TokenCache = oAuth2TokenCache;

        this.httpClient = httpClient;
        this.objectMapper = objectMapper;

        this.context = context;

        retrieveAllAccessTokens(context);

        this.httpClient.start();
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
        } while (retryCount < 5);

        // Close the application after 5 attempts to get the accessTokens
        SpringApplication.exit(context, () -> -2);
    }

    /**
     * Handles the callback for a subscription event message by sending it to
     * the specified callbackUrl.
     *
     * @param subscriptionEventMessage The subscription event message to be delivered.
     * @param callbackUrl              The callbackUrl to which the event should be sent.
     * @throws CallbackException If there is an error during the callback process.
     * @throws IOException       If an IO error occurs while making the HTTP request.
     */
    public void callback(final SubscriptionEventMessage subscriptionEventMessage, final String callbackUrl) throws CallbackException, IOException, CouldNotFetchAccessTokenException {
        var payload = objectMapper.writeValueAsBytes(subscriptionEventMessage.getEvent());

        var requestBuilder = AsyncRequestBuilder.post(callbackUrl);
        addHeaderToRequest(requestBuilder, subscriptionEventMessage.getHttpHeaders(), subscriptionEventMessage.getEnvironment());
        var request = requestBuilder
                .setEntity(new BasicAsyncEntityProducer(payload, ContentType.APPLICATION_JSON))
                .build();

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
    private void executeRequest(final String callbackUrl, final AsyncRequestProducer request) throws IOException, CallbackException {
        var future = httpClient.execute(request, new BasicResponseConsumer<Void>(new DiscardingEntityConsumer<>()), null);
        HttpResponse response;
        try {
            response = future.get().getHead();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Delivery request to '" + callbackUrl + "' was interrupted", e);
        } catch (ExecutionException e) {
            final var cause = e.getCause();
            throw new IOException("Delivery request to '" + callbackUrl + "' failed", cause);
        }

        final var successfulStatusCodes = cometConfig.getSuccessfulStatusCodes();
        final var statusCode = response.getCode();
        if (!successfulStatusCodes.contains(statusCode)) {
            throw new CallbackException("Error while delivering event to callback '" + callbackUrl + "': " + response.getReasonPhrase(), statusCode);
        }
    }

    /**
     * Overrides a header in the HTTP request.
     *
     * @param request The HTTP request to be modified.
     * @param key     The header key to be overridden.
     * @param value   The new value for the header.
     */
    private void overrideHeader(AsyncRequestBuilder request, String key, String value) {
        request.removeHeaders(key);
        request.setHeader(new BasicHeader(key, value));
    }

    /**
     * Adds headers to the HTTP request based on the provided map and environment.
     *
     * @param requestBuilder     The HTTP request to which headers are added.
     * @param httpHeaders The map of headers to be added.
     * @param environment The environment for which headers are added.
     */
    private void addHeaderToRequest(AsyncRequestBuilder requestBuilder, Map<String, List<String>> httpHeaders, String environment) throws CouldNotFetchAccessTokenException {
        if (httpHeaders != null) {
            httpHeaders.forEach((k, v) -> {
                if (cometConfig.getHeaderPropagationBlacklist().stream().noneMatch(k::matches)) {
                    v.forEach(e -> requestBuilder.addHeader(k, e));
                }
            });
        }

        overrideHeader(requestBuilder, HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        overrideHeader(requestBuilder, HttpHeaders.AUTHORIZATION, "Bearer " + oAuth2TokenCache.getToken(Optional.ofNullable(environment).orElse("default")));
        overrideHeader(requestBuilder, HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        overrideHeader(requestBuilder, HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.displayName());

        tracer.getCurrentTracingHeaders().forEach((key, value) -> overrideHeader(requestBuilder, key, value));
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
