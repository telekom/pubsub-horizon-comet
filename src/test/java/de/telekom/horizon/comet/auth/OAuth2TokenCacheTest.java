// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.horizon.comet.exception.BadTokenResponseException;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.exception.TokenRequestErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuth2TokenCacheTest {
    ObjectMapper objectMapper = new ObjectMapper();

    String validTokenResponse = """
            {
                "access_token": "mockedAccessToken",
                "expires_in": 3600
            }
            """;

    String invalidTokenResponse = "foo: bar";

    @Test
    void shouldRetrieveAccessTokenSuccessfully() {
        RestTemplate restTemplateMock = mock(RestTemplate.class);

        when(restTemplateMock.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class))).thenReturn(new ResponseEntity<>(validTokenResponse, HttpStatus.OK));

        OAuth2TokenCache oAuth2TokenCache = new OAuth2TokenCache("", "", "foo=bar", objectMapper, restTemplateMock);

        assertDoesNotThrow(() -> oAuth2TokenCache.retrieveAccessToken("testEnvironment"));
    }

    @Test
    void shouldThrowCouldNotFetchAccessTokenException() {
        RestTemplate restTemplateMock = mock(RestTemplate.class);

        when(restTemplateMock.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class))).thenThrow(new RestClientException("message"));

        OAuth2TokenCache oAuth2TokenCache = new OAuth2TokenCache("", "", "foo=bar", objectMapper, restTemplateMock);

        assertThrows(CouldNotFetchAccessTokenException.class, () -> oAuth2TokenCache.retrieveAccessToken("testEnvironment"));
    }

    @Test
    void shouldThrowTokenRequestErrorException() {
        RestTemplate restTemplateMock = mock(RestTemplate.class);

        when(restTemplateMock.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class))).thenReturn(new ResponseEntity<>(validTokenResponse, HttpStatus.UNAUTHORIZED));

        OAuth2TokenCache oAuth2TokenCache = new OAuth2TokenCache("", "", "foo=bar", objectMapper, restTemplateMock);

        assertThrows(TokenRequestErrorException.class, () -> oAuth2TokenCache.retrieveAccessToken("testEnvironment"));
    }

    @Test
    void shouldThrowBadTokenResponseException() {
        RestTemplate restTemplateMock = mock(RestTemplate.class);

        when(restTemplateMock.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class))).thenReturn(new ResponseEntity<>(invalidTokenResponse, HttpStatus.OK));

        OAuth2TokenCache oAuth2TokenCache = new OAuth2TokenCache("", "", "foo=bar", objectMapper, restTemplateMock);

        assertThrows(BadTokenResponseException.class, () -> oAuth2TokenCache.retrieveAccessToken("testEnvironment"));
    }
}
