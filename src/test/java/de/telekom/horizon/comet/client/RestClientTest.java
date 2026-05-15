// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.exception.CallbackException;
import de.telekom.horizon.comet.service.DeliveryTask;
import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import de.telekom.horizon.comet.test.utils.HazelcastTestInstance;
import de.telekom.horizon.comet.test.utils.ObjectGenerator;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static de.telekom.horizon.comet.test.utils.ObjectGenerator.generateCallbackSubscriptionEventMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, HazelcastTestInstance.class})
class RestClientTest extends AbstractIntegrationTest {
    @Mock
    CometConfig cometConfig;
    @Mock
    HorizonTracer horizonTracer;
    @Mock
    OAuth2TokenCache oAuth2TokenCache;
    @Mock
    CloseableHttpAsyncClient closeableHttpClient;
    @Mock
    Future<Message<HttpResponse,Void>> responseFuture;
    @Mock
    Message<HttpResponse,Void> responseMessage;
    @Mock
    HttpResponse response;
    @Mock
    RequestChannel requestChannel;
    @Mock
    ApplicationContext context;

    RestClient restClient;

    @BeforeEach
    void initMock() throws InterruptedException, ExecutionException {
        when(cometConfig.getHeaderPropagationBlacklist()).thenReturn(List.of("x-spacegate-token", "authorization", "content-length", "host", "accept.*", "x-forwarded.*"));
        when(closeableHttpClient
                .execute(any(AsyncRequestProducer.class),
                        ArgumentMatchers.<AsyncResponseConsumer<Message<HttpResponse,Void>>>any(),
                        any()))
                .thenReturn(responseFuture);
        this.restClient = spy(new RestClient(cometConfig, horizonTracer, oAuth2TokenCache, closeableHttpClient, new ObjectMapper(), context));
    }

    @ParameterizedTest(name = "Check Header for {0}")
    @EnumSource(value = HttpStatus.class, names = {"CREATED", "OK", "ACCEPTED", "NO_CONTENT"}, mode = EnumSource.Mode.INCLUDE)
    @DisplayName("Send successful callback without tardis headers")
    void sendSuccessfulRequestWithoutTardisHeader(HttpStatus httpStatus) throws IOException, HttpException {
        AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        doAnswer(invocation -> {
                    capturedRequest.set(invocation.getArgument(0));
                    return null;
                })
                .when(requestChannel)
                .sendRequest(any(), any(), any());

        assertDoesNotThrow(() -> restClient.callback(generateCallbackSubscriptionEventMessage(), "https://test.de", null));

        // check if internal headers are not in request
        verify(closeableHttpClient).execute(
                argThat(request -> {
                    var internalHttpHeaders = ObjectGenerator.getInternalHttpHeaders();
                    try {
                        request.sendRequest(requestChannel, null);
                    } catch (HttpException | IOException e) {
                        throw new RuntimeException(e);
                    }
                    var httpHeaders = capturedRequest.get().getHeaders();
                    return Arrays.stream(httpHeaders).noneMatch(header -> internalHttpHeaders.containsKey(header.getName()));
                }),
                ArgumentMatchers.<AsyncResponseConsumer<Message<HttpResponse,Void>>>any(),
                any());

        // check if all external headers are in request
        verify(closeableHttpClient).execute(
                argThat(request -> {
                    var externalHeaders = ObjectGenerator.getExternalHeaders();
                    try {
                        request.sendRequest(requestChannel, null);
                    } catch (HttpException | IOException e) {
                        throw new RuntimeException(e);
                    }
                    var httpHeaders = capturedRequest.get().getHeaders();
                    return Arrays.stream(httpHeaders).anyMatch(header -> externalHeaders.containsKey(header.getName()));
                }),
                ArgumentMatchers.<AsyncResponseConsumer<Message<HttpResponse,Void>>>any(),
                any());



        // check if Headers are overwritten successfully
        verify(closeableHttpClient).execute(
                argThat(request -> {
                    try {
                        request.sendRequest(requestChannel, null);
                    } catch (HttpException | IOException e) {
                        throw new RuntimeException(e);
                    }
                    var httpHeaders = capturedRequest.get().getHeaders();
                    return Arrays
                            .stream(httpHeaders)
                            .anyMatch(header ->
                                    header.getName().equals(HttpHeaders.CONTENT_TYPE) &&
                                    header.getValue().equals(MediaType.APPLICATION_JSON_VALUE));
                }),
                ArgumentMatchers.<AsyncResponseConsumer<Message<HttpResponse,Void>>>any(),
                any());
    }

}
