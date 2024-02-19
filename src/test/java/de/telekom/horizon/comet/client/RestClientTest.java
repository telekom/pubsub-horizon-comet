// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.horizon.comet.auth.OAuth2TokenCache;
import de.telekom.horizon.comet.config.CometConfig;
import de.telekom.horizon.comet.exception.CallbackException;
import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import de.telekom.horizon.comet.test.utils.HorizonTestHelper;
import de.telekom.horizon.comet.test.utils.ObjectGenerator;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static de.telekom.horizon.comet.test.utils.ObjectGenerator.generateCallbackSubscriptionEventMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestClientTest extends AbstractIntegrationTest {
    @Mock
    CometConfig cometConfig;
    @Mock
    HorizonTracer horizonTracer;
    @Mock
    OAuth2TokenCache oAuth2TokenCache;
    @Mock
    CloseableHttpClient closeableHttpClient;
    @Mock
    CloseableHttpResponse closeableHttpResponse;
    @Mock
    StatusLine statusLine;
    @Mock
    ApplicationContext context;

    RestClient restClient;
    @BeforeEach
    void initMock() throws IOException, InterruptedException {
        when(cometConfig.getSuccessfulStatusCodes()).thenReturn(List.of(200, 201, 202, 204));
        when(cometConfig.getHeaderPropagationBlacklist()).thenReturn(List.of("x-spacegate-token","authorization","content-length","host","accept.*","x-forwarded.*"));
        when(closeableHttpResponse.getStatusLine()).thenReturn(statusLine);
        when(closeableHttpClient.execute(any())).thenReturn(closeableHttpResponse);
        this.restClient = spy(new RestClient(cometConfig, horizonTracer, oAuth2TokenCache, closeableHttpClient, new ObjectMapper(), context));
    }

    @ParameterizedTest(name = "Check Header for {0}")
    @EnumSource(value = HttpStatus.class, names = {"CREATED", "OK", "ACCEPTED", "NO_CONTENT"}, mode = EnumSource.Mode.INCLUDE)
    @DisplayName("Send successful callback without tardis headers")
    void sendSuccessfulRequestWithoutTardisHeader(HttpStatus httpStatus) throws IOException {
        when(statusLine.getStatusCode()).thenReturn(httpStatus.value());

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage("successull", getEventType());
        assertDoesNotThrow(() -> restClient.callback(generateCallbackSubscriptionEventMessage(), "https://test.de", context));

        // check if internal headers are not in request
        verify(closeableHttpClient).execute(argThat(request -> {
            var internalHttpHeaders = ObjectGenerator.getInternalHttpHeaders();
            var httpHeaders = request.getAllHeaders();
            return Arrays.stream(httpHeaders).noneMatch(header -> internalHttpHeaders.containsKey(header.getName()));
        }));

        // check if all external headers are in request
        verify(closeableHttpClient).execute(argThat(request -> {
            var externalHeaders = ObjectGenerator.getExternalHeaders();
            var httpHeaders = request.getAllHeaders();
            return Arrays.stream(httpHeaders).anyMatch(header -> externalHeaders.containsKey(header.getName()));
        }));

        // check if Headers are overwritten successfully
        verify(closeableHttpClient).execute(argThat(request -> {
            var httpHeaders = request.getAllHeaders();
            return Arrays.stream(httpHeaders).anyMatch(header -> header.getName().equals(HttpHeaders.CONTENT_TYPE) && header.getValue().equals(MediaType.APPLICATION_JSON_VALUE));
        }));
    }

    @ParameterizedTest(name = "Check CallbackException for {0}")
    @EnumSource(value = HttpStatus.class, names = {"CREATED", "OK", "ACCEPTED", "NO_CONTENT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Throw callbackException including the right status code for unsuccessful request")
    void throwCallbackExceptionForUnsuccessfulRequest(HttpStatus httpStatus) {
        when(statusLine.getStatusCode()).thenReturn(httpStatus.value());

        var callbackException = assertThrows(CallbackException.class, () -> restClient.callback(generateCallbackSubscriptionEventMessage(), "https://test.de", context));
        assertEquals(httpStatus.value(), callbackException.getStatusCode());
    }

}
