// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.matching.ContainsPattern;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.StatusMessage;
import de.telekom.horizon.comet.cache.DeliveryTargetInformation;
import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import de.telekom.horizon.comet.test.utils.HazelcastTestInstance;
import de.telekom.horizon.comet.test.utils.HorizonTestHelper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static de.telekom.horizon.comet.test.utils.WiremockStubs.stubOidc;
import static de.telekom.horizon.comet.utils.MessageUtils.isStatusMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(HazelcastTestInstance.class)
class DeliveryErrorTests extends AbstractIntegrationTest {

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
    void testFailedStateOnBlankCallbackUrl(int status) throws InterruptedException, JsonProcessingException {
        // given
        final String subscriptionId = "blank-callback-"+status;
        final String blankCallback = "";

        when(callbackUrlCache.getDeliveryTargetInformation(any())).thenReturn(Optional.of(
                new DeliveryTargetInformation(wireMockServer.baseUrl() + blankCallback, "callback", false, null))
        );

        SubscriptionResource subscriptionResource = HorizonTestHelper.createDefaultSubscriptionResource("playground", getEventType());
        subscriptionResource.getSpec().getSubscription().setSubscriptionId(subscriptionId);
        subscriptionResource.getSpec().getSubscription().setCallback(blankCallback);

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage(subscriptionId, getEventType());

        // when
        assertDoesNotThrow(() -> simulateNewPublishedEvent(subscriptionMessage));

        //then
        // collect latest status messages for eventType
        ArrayList<Status> statusFlow = new ArrayList<>();
        var numberOfStatusMessagesSent = 0;
        ConsumerRecord<String, String> statusRecord;

        while((statusRecord = pollForRecord(20, TimeUnit.SECONDS)) != null) {
            if (isStatusMessage(statusRecord)) {
                StatusMessage lastStatusMessage = objectMapper.readValue(statusRecord.value(), StatusMessage.class);
                statusFlow.add(lastStatusMessage.getStatus());
                numberOfStatusMessagesSent += 1;
            }
        }

        // the last message should be waiting status
        assertArrayEquals(new Status[]{Status.DELIVERING, Status.FAILED}, statusFlow.toArray());

        // we expect only two status message
        assertEquals(2, numberOfStatusMessagesSent);

        // cb should then be open for subscription id
        assertFalse(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId));
    }

    @Test
    void testDropDuplicateAndSendStatusMessage() throws InterruptedException, JsonProcessingException {
        // given
        final String subscriptionId = "duplicate-check";
        final String callbackPath = "/duplicate";

        when(callbackUrlCache.getDeliveryTargetInformation(any())).thenReturn(Optional.of(
                new DeliveryTargetInformation(wireMockServer.baseUrl() + callbackPath, "callback", false, null))
        );

        wireMockServer.stubFor(
                post(callbackPath).willReturn(aResponse().withStatus(HttpStatus.OK.value()))
        );

        SubscriptionResource subscriptionResource = HorizonTestHelper.createDefaultSubscriptionResource("playground", getEventType());
        subscriptionResource.getSpec().getSubscription().setSubscriptionId(subscriptionId);
        subscriptionResource.getSpec().getSubscription().setCallback(wireMockServer.baseUrl() + callbackPath);

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage(subscriptionId, getEventType());

        assertDoesNotThrow(() -> simulateNewPublishedEvent(subscriptionMessage));

        // collect latest status messages for eventType
        ConsumerRecord<String, String> statusRecord;
        var statusMessagesReceived = 0;

        while((statusRecord = pollForRecord(10, TimeUnit.SECONDS)) != null) {
            if (isStatusMessage(statusRecord)) {
                statusMessagesReceived += 1;
            }
        }
        // assert count of status messages because order of status messages can not be guaranteed
        assertEquals(2, statusMessagesReceived);

        // when message is send a second time
        assertDoesNotThrow(() -> {
            subscriptionMessage.setUuid(UUID.randomUUID().toString());
            simulateNewPublishedEvent(subscriptionMessage);
        });

        //then
        // collect latest status messages for eventType
        var duplicateStatusMessagesReceived = 0;
        StatusMessage statusMessage = null;
        while((statusRecord = pollForRecord(10, TimeUnit.SECONDS)) != null) {
            if (isStatusMessage(statusRecord)) {
                duplicateStatusMessagesReceived += 1;
                statusMessage = objectMapper.readValue(statusRecord.value(), StatusMessage.class);
            }
        }
        // assert count of status messages because order of status messages can not be guaranteed
        assertEquals(1, duplicateStatusMessagesReceived);
        assertNotNull(statusMessage);
        assertEquals(Status.DUPLICATE, statusMessage.getStatus());

        // cb should then be open for subscription id
        assertFalse(circuitBreakerCacheService.isCircuitBreakerOpenOrChecking(subscriptionId));

        // !! only one request should be done towards callback
        wireMockServer.verify(
                exactly(1),
                postRequestedFor(
                        urlPathEqualTo(callbackPath)
                ).withRequestBody(new ContainsPattern("myfancydata"))
        );
    }
}
