// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.test.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.model.db.Coordinates;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjectGenerator {

    public final static String TEST_SUBSCRIPTION_TOPIC = "subscribed";
    public final static String TEST_SUBSCRIPTION_ID = "123-456-789";
    public final static String TEST_EVENT_ID = "336d55df-86ff-4529-a2df-92b66a124465";
    public final static String TEST_ENVIRONMENT = "bond";

    public final static DeliveryType TEST_DELIVERY_TYPE = DeliveryType.CALLBACK;

    public static SubscriptionEventMessage generateCallbackSubscriptionEventMessage() {
        return generateSubscriptionEventMessage(DeliveryType.CALLBACK, true);
    }

    public static SubscriptionEventMessage generateSubscriptionEventMessage(DeliveryType deliveryType, boolean withAdditionalFields) {
        var subscriptionEventMessage = new SubscriptionEventMessage();

        subscriptionEventMessage.setUuid(TEST_EVENT_ID);
        subscriptionEventMessage.setEnvironment(TEST_ENVIRONMENT);
        subscriptionEventMessage.setSubscriptionId(TEST_SUBSCRIPTION_ID);
        subscriptionEventMessage.setDeliveryType(deliveryType);
        if (withAdditionalFields) {
            subscriptionEventMessage.setAdditionalFields(Map.of("callback-url", "foo.tld/bar"));
        }
        subscriptionEventMessage.setHttpHeaders(getAllHttpHeaders());

        return subscriptionEventMessage;
    }

    public static Coordinates generateCoordinates() {
        return new Coordinates(123, 456);
    }

    public static ConsumerRecord<String, String> generateConsumerRecord(DeliveryType deliveryType) throws JsonProcessingException {
        var msg = generateSubscriptionEventMessage(deliveryType, true);
        var objectmapper = new ObjectMapper();
        String json = objectmapper.writeValueAsString(msg);
        return new ConsumerRecord<>(TEST_SUBSCRIPTION_TOPIC, 0, 0L, msg.getUuid(), json);
    }

    private static Map<String, List<String>> getAllHttpHeaders() {
        var httpHeaders = new HashMap<String, List<String>>();
        httpHeaders.putAll(getExternalHeaders());
        httpHeaders.putAll(getInternalHttpHeaders());

        return httpHeaders;
    }

    public static Map<String, List<String>> getExternalHeaders() {
        var httpHeaders = new HashMap<String, List<String>>();
        httpHeaders.put("x-event-id", List.of(TEST_EVENT_ID));
        httpHeaders.put("x-event-type", List.of("pandora.smoketest.aws.v1"));
        httpHeaders.put("x-pubsub-publisher-id", List.of("eni--pandora--pandora-smoketest-aws-publisher"));
        httpHeaders.put("x-pubsub-subscriber-id", List.of("eni--pandora--pandora-smoketest-aws-subscriber-01"));

        return httpHeaders;
    }

    public static Map<String, List<String>> getInternalHttpHeaders() {
        var httpHeaders = new HashMap<String, List<String>>();

        //internal headers
        httpHeaders.put("x-spacegate-token", List.of("xxx-xxx-xxx"));
        httpHeaders.put("authorization", List.of("granted"));
        httpHeaders.put("content-length", List.of("high"));
        httpHeaders.put("host", List.of("tester"));
        httpHeaders.put("accept.test", List.of("yes"));
        httpHeaders.put("x-forwarded.test", List.of("stargate"));

        return httpHeaders;
    }

}
