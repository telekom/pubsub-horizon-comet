package de.telekom.horizon.comet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.model.event.*;
import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import de.telekom.horizon.comet.test.utils.HorizonTestHelper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static de.telekom.horizon.comet.test.utils.WiremockStubs.stubOidc;
import static de.telekom.horizon.comet.utils.MessageUtils.isStatusMessage;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTests extends AbstractIntegrationTest {

    @AfterEach
    void afterEach() {
        wireMockServer.resetAll();
        stubOidc(wireMockServer);
    }

    @Autowired
    private CircuitBreakerCacheService circuitBreakerCacheService;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(ints = {401,429,502,503,504})
    void testSuccessfulOpenCircuitForSubscriptionOnHttpStatus(int status) throws InterruptedException, JsonProcessingException {
        // given
        final String subscriptionId = "opencircuit-"+status;
        final String callbackPath = "/callbacktest2";

        wireMockServer.stubFor(
                post(callbackPath).willReturn(aResponse().withStatus(HttpStatus.valueOf(status).value()))
        );

        SubscriptionResource subscriptionResource = HorizonTestHelper.createDefaultSubscriptionResource("playground", getEventType());
        subscriptionResource.getSpec().getSubscription().setSubscriptionId(subscriptionId);
        subscriptionResource.getSpec().getSubscription().setCallback(wireMockServer.baseUrl() + callbackPath);
        addTestSubscription(subscriptionResource);

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage(subscriptionId, getEventType());

        // when
        assertDoesNotThrow(() -> simulateNewPublishedEvent(subscriptionMessage));

        //then
        await().atMost(Duration.ofSeconds(15)).until(() -> circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId));
        // we tried 3 times calling callback
        wireMockServer.verify(
                exactly(3),
                postRequestedFor(
                        urlPathEqualTo(callbackPath)
                )
        );
        // cb should then be open for subscription id
        assertTrue(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId));

        // collect latest status messages for eventType
        var statusFlow = new ArrayList<Status>();
        ConsumerRecord<String, String> statusRecord;
        while((statusRecord = pollForRecord(10, TimeUnit.SECONDS)) != null) {
            if (isStatusMessage(statusRecord)) {
                StatusMessage lastStatusMessage = objectMapper.readValue(statusRecord.value(), StatusMessage.class);
                statusFlow.add(lastStatusMessage.getStatus());
            }
        }

        // the last message should be waiting status
        assertArrayEquals(new Status[]{Status.DELIVERING, Status.WAITING}, statusFlow.toArray());
    }

    @ParameterizedTest
    @ValueSource(ints = {401,429,502,503,504})
    void testSuccessfulOpenCircuitBreakerBypassForSubscriptionOnHttpStatus(int status) throws InterruptedException, JsonProcessingException {
        // given
        final String subscriptionId = "opencircuit-bypass-"+status;
        final String callbackPath = "/callbacktest3";

        wireMockServer.stubFor(
                post(callbackPath).willReturn(aResponse().withStatus(HttpStatus.valueOf(status).value()))
        );

        SubscriptionResource subscriptionResource = HorizonTestHelper.createDefaultSubscriptionResource("playground", getEventType());
        subscriptionResource.getSpec().getSubscription().setSubscriptionId(subscriptionId);
        subscriptionResource.getSpec().getSubscription().setCircuitBreakerOptOut(true);
        subscriptionResource.getSpec().getSubscription().setCallback(wireMockServer.baseUrl() + callbackPath);
        addTestSubscription(subscriptionResource);

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage(subscriptionId, getEventType());

        // when
        assertDoesNotThrow(() -> simulateNewPublishedEvent(subscriptionMessage));

        //then
        // collect latest status messages for eventType
        var statusFlow = new ArrayList<Status>();
        ConsumerRecord<String, String> statusRecord;
        while((statusRecord = pollForRecord(10, TimeUnit.SECONDS)) != null) {
            if (isStatusMessage(statusRecord)) {
                StatusMessage lastStatusMessage = objectMapper.readValue(statusRecord.value(), StatusMessage.class);
                statusFlow.add(lastStatusMessage.getStatus());
            }

        }
        // the last message should be failed status
        assertArrayEquals(new Status[]{Status.DELIVERING, Status.FAILED}, statusFlow.toArray());

        // we tried 3 times calling callback
        wireMockServer.verify(
                exactly(3),
                postRequestedFor(
                        urlPathEqualTo(callbackPath)
                )
        );
        // cb should then be open for subscription id
        assertFalse(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId));
    }

    @Test
    void testDontSendOnOpenCircuitBreaker() throws InterruptedException, JsonProcessingException {
        // given
        final String subscriptionId = "opencircuit-dontsend";
        final String callbackPath = "/callbacktest5";

        wireMockServer.stubFor(
                post(callbackPath).willReturn(aResponse().withStatus(HttpStatus.OK.value()))
        );

        SubscriptionResource subscriptionResource = HorizonTestHelper.createDefaultSubscriptionResource("playground", getEventType());
        subscriptionResource.getSpec().getSubscription().setSubscriptionId(subscriptionId);
        subscriptionResource.getSpec().getSubscription().setCircuitBreakerOptOut(false);
        subscriptionResource.getSpec().getSubscription().setCallback(wireMockServer.baseUrl() + callbackPath);
        addTestSubscription(subscriptionResource);

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage(subscriptionId, getEventType());

        circuitBreakerCacheService.openCircuitBreaker(subscriptionId, wireMockServer.baseUrl() + callbackPath, "playground");

        // when
        assertDoesNotThrow(() -> simulateNewPublishedEvent(subscriptionMessage));

        //then

        // collect latest status messages for eventType
        var statusFlow = new ArrayList<Status>();
        ConsumerRecord<String, String> statusRecord;
        while((statusRecord = pollForRecord(5, TimeUnit.SECONDS)) != null) {
            if (isStatusMessage(statusRecord)) {
                StatusMessage lastStatusMessage = objectMapper.readValue(statusRecord.value(), StatusMessage.class);
                statusFlow.add(lastStatusMessage.getStatus());
            }
        }
        // the last message should be waiting status
        assertArrayEquals(new Status[]{Status.WAITING}, statusFlow.toArray());

        // we tried 0 times calling callback cause circuit is open
        wireMockServer.verify(
                exactly(0),
                postRequestedFor(
                        urlPathEqualTo(callbackPath)
                )
        );
    }
}
