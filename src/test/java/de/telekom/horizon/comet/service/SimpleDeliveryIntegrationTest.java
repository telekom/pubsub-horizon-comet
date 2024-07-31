// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.matching.ContainsPattern;
import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.github.tomakehurst.wiremock.matching.RegexPattern;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.metrics.AdditionalFields;
import de.telekom.eni.pandora.horizon.metrics.MetricNames;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.StatusMessage;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.horizon.comet.cache.DeliveryTargetInformation;
import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import de.telekom.horizon.comet.test.utils.HorizonTestHelper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static de.telekom.horizon.comet.test.utils.WiremockStubs.stubOidc;
import static de.telekom.horizon.comet.utils.MessageUtils.isStatusMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SimpleDeliveryIntegrationTest extends AbstractIntegrationTest {

    private final static String TRACING_HEADER_NAME = "x-b3-traceid";

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void afterEach() {
        wireMockServer.resetAll();
        stubOidc(wireMockServer);
    }

    @ParameterizedTest()
    @EnumSource(EventRetentionTime.class)
    void testSimplestDelivery(EventRetentionTime eventRetentionTime) throws Exception {
        // given
        final String subscriptionId = "fixedsubcriptionid";
        final String callbackPath = "/callbacktest1";
        final String testHeader = "integrationtestheadervalue";
        final String traceId = UUID.randomUUID().toString();

        when(callbackUrlCache.getDeliveryTargetInformation(any())).thenReturn(Optional.of(
                new DeliveryTargetInformation(wireMockServer.baseUrl() + callbackPath, false)));

        wireMockServer.stubFor(
                post(callbackPath).willReturn(aResponse().withStatus(HttpStatus.OK.value()))
        );

        SubscriptionResource subscriptionResource = HorizonTestHelper.createDefaultSubscriptionResource("playground", getEventType());
        subscriptionResource.getSpec().getSubscription().setSubscriptionId(subscriptionId);
        subscriptionResource.getSpec().getSubscription().setCallback(wireMockServer.baseUrl() + callbackPath);

        var subscriptionMessage = HorizonTestHelper.createDefaultSubscriptionEventMessage(subscriptionId, getEventType());
        subscriptionMessage.setEventRetentionTime(eventRetentionTime);
        Map<String, Object> additionalFields = new HashMap<>();
        additionalFields.put(AdditionalFields.START_TIME_TRUSTED.getValue(), System.currentTimeMillis());
        subscriptionMessage.setAdditionalFields(additionalFields);
        subscriptionMessage.setHttpHeaders(Map.of(TRACING_HEADER_NAME, List.of(traceId), "X-Test-Pandora-Foo", List.of(testHeader)));

        // when
        assertDoesNotThrow(() -> simulateNewPublishedEvent(subscriptionMessage));

        ConsumerRecord<String, String> lastRecord = null;
        for(ConsumerRecord<String, String> record = pollForRecord(3, TimeUnit.SECONDS); Objects.nonNull(record); record = pollForRecord(5, TimeUnit.SECONDS)) {
            assertEquals(eventRetentionTime.getTopic(), record.topic());
            lastRecord = record;
        }

        //then
        assertNotNull(lastRecord);
        assertTrue(isStatusMessage(lastRecord));

        StatusMessage deliveredStatusMessage = objectMapper.readValue(lastRecord.value(), StatusMessage.class);
        assertEquals(Status.DELIVERED, deliveredStatusMessage.getStatus());
        assertNotNull(deliveredStatusMessage.getAdditionalFields());
        assertNotNull(deliveredStatusMessage.getAdditionalFields().get(MetricNames.EndToEndLatencyTardis.getAsHeaderValue()));
        assertNotNull(deliveredStatusMessage.getAdditionalFields());
        assertNotNull(deliveredStatusMessage.getAdditionalFields().get(MetricNames.EndToEndLatencyCustomer.getAsHeaderValue()));

        wireMockServer.verify(
                exactly(1),
                postRequestedFor(
                        urlPathEqualTo(callbackPath)
                ).withHeader("X-Test-Pandora-Foo", new EqualToPattern(testHeader))
                        .withHeader(HttpHeaders.CONTENT_TYPE, new EqualToPattern(MediaType.APPLICATION_JSON_VALUE))
                        .withHeader(HttpHeaders.AUTHORIZATION, new RegexPattern("Bearer .*"))
                        .withHeader(HttpHeaders.ACCEPT, new EqualToPattern(MediaType.APPLICATION_JSON_VALUE))
                        .withHeader(HttpHeaders.ACCEPT_CHARSET, new EqualToPattern(StandardCharsets.UTF_8.displayName()))
                        // tracing is only used from kafka message header which cant be sent with eventwriter
                        //.withHeader(TRACING_HEADER_NAME, new EqualToPattern(traceId))
                        .withRequestBody(new ContainsPattern("myfancydata"))

        );

        MvcResult mvcResult = mockmvc.perform(MockMvcRequestBuilders.get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        String resultContent = new String(mvcResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertNotNull(resultContent);
        assertTrue(resultContent.contains("executor_active_threads{name=\"deliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_active_threads{name=\"redeliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_pool_size_threads{name=\"deliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_pool_size_threads{name=\"redeliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_queued_tasks{name=\"deliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_queued_tasks{name=\"redeliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_queue_remaining_tasks{name=\"deliveryTaskExecutor\""));
        assertTrue(resultContent.contains("executor_queue_remaining_tasks{name=\"redeliveryTaskExecutor\""));
    }
}
