// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.auth;

import com.github.tomakehurst.wiremock.matching.AnythingPattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import de.telekom.horizon.comet.config.rest.AuthProperties;
import de.telekom.horizon.comet.exception.CouldNotFetchAccessTokenException;
import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.telekom.horizon.comet.test.utils.WiremockStubs.stubOidc;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test for the retry behavior of OAuth2TokenCache.
 * This test verifies the custom retry logic that has replaced the @Retryable annotation.
 */
@DirtiesContext
public class OAuth2TokenCacheRetryTest extends AbstractIntegrationTest {

    @Autowired
    private OAuth2TokenCache oAuth2TokenCache;

    @Autowired
    private AuthProperties authProperties;

    @BeforeEach
    void setUpTest() {
        oAuth2TokenCache.resetTokenMap();
        authProperties.setTokenUri("http://localhost:" + wireMockServer.getPort() + "/oidc");
        wireMockServer.resetAll();
        wireMockServer.resetScenarios();
    }

    @Test
    public void testSuccessfulRetryAfterInitialFailure() throws CouldNotFetchAccessTokenException {

        wireMockServer.stubFor(post("/oidc")
                .inScenario("SequentialResponse")
                .whenScenarioStateIs(Scenario.STARTED)  // 1st call
                .withRequestBody(new AnythingPattern())
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo("SecondCall"));

        wireMockServer.stubFor(post("/oidc")
                .inScenario("SequentialResponse")
                .whenScenarioStateIs("SecondCall")  // 2nd call
                .withRequestBody(new AnythingPattern())
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo("Success"));

        wireMockServer.stubFor(post("/oidc")
                .inScenario("SequentialResponse")
                .whenScenarioStateIs("Success")  // 3rd+ calls
                .withRequestBody(new AnythingPattern())
                .willReturn(aResponse().withStatus(HttpStatus.OK.value()).withBody("""
                        {
                            "expires_in": 1000,
                            "access_token": "foobar"
                        }
                        """)));

        System.out.println("MAPPINGs:");
        System.out.println("PORT: " + wireMockServer.getPort());
        wireMockServer.getStubMappings().forEach(System.out::println);


        // ACT
        String token = oAuth2TokenCache.getToken("");

        // ASSERT
        assertThat(token).isEqualTo("foobar");
        wireMockServer.verify(3, postRequestedFor(urlPathEqualTo("/oidc")));
    }

    @Test
    public void testFailureAfterMaxRetries() {

        wireMockServer.stubFor(post("/oidc")
                .withRequestBody(new AnythingPattern())
                .willReturn(aResponse().withStatus(404)));

        // ACT
        assertThrows(CouldNotFetchAccessTokenException.class, () -> oAuth2TokenCache.getToken(""));
        wireMockServer.verify(3, postRequestedFor(urlPathEqualTo("/oidc")));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.resetAll();
        wireMockServer.resetScenarios();
        oAuth2TokenCache.resetTokenMap();
        stubOidc(wireMockServer);
    }

}
