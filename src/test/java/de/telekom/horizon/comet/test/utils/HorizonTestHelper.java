package de.telekom.horizon.comet.test.utils;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Event;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;

import java.util.Map;
import java.util.UUID;

public class HorizonTestHelper {

    public static SubscriptionResource createDefaultSubscriptionResource(final String environment, final String eventType) {
        var subscriptionRes = new SubscriptionResource();
        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment(environment);
        var subscription = new Subscription();
        subscription.setSubscriptionId(UUID.randomUUID().toString());
        subscription.setSubscriberId(UUID.randomUUID().toString());
        subscription.setCallback("https://localhost:4711/foobar");
        subscription.setType(eventType);
        subscription.setDeliveryType("callback");
        subscription.setPayloadType("data");
        subscription.setPublisherId(UUID.randomUUID().toString());
        spec.setSubscription(subscription);

        subscriptionRes.setSpec(spec);

        return subscriptionRes;
    }

    public static SubscriptionResource createDefaultSubscriptionResourceWithSubscriberId(final String environment, final String eventType, String subscriberId) {
        SubscriptionResource resource = createDefaultSubscriptionResource(environment, eventType);
        resource.getSpec().getSubscription().setSubscriberId(subscriberId);
        return resource;
    }

    public static SubscriptionEventMessage createDefaultSubscriptionEventMessage(String subscriptionId, String eventType) {
        Event event = new Event();
        event.setId(UUID.randomUUID().toString());
        event.setType(eventType);
        event.setData("""
                {
                    "myfancydata": "foo"
                }
                """);

        var subscriptionMessage = new SubscriptionEventMessage();
        subscriptionMessage.setSubscriptionId(subscriptionId);
        subscriptionMessage.setMultiplexedFrom(UUID.randomUUID().toString());
        subscriptionMessage.setDeliveryType(DeliveryType.CALLBACK);
        subscriptionMessage.setEnvironment("playground");
        subscriptionMessage.setUuid(UUID.randomUUID().toString());
        subscriptionMessage.setHttpHeaders(Map.of());
        subscriptionMessage.setEvent(event);
        return subscriptionMessage;
    }

}
