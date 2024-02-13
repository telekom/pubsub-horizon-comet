package de.telekom.horizon.comet.kubernetes;

import de.telekom.eni.pandora.horizon.kubernetes.InformerStoreInitSupport;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.horizon.comet.cache.CallbackCacheProperties;
import de.telekom.horizon.comet.cache.CallbackUrlCache;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * The {@code SubscriptionResourceEventHandler} class is responsible for handling events related to {@link SubscriptionResource} instances.
 */
@Service
@Slf4j
public class SubscriptionResourceEventHandler implements ResourceEventHandler<SubscriptionResource>, InformerStoreInitSupport {

    /**
     * The cache for storing callback URLs associated with subscriptionIds.
     */
    private final CallbackUrlCache callbackUrlCache;

    /**
     * Constructs a new {@code SubscriptionResourceEventHandler} with the specified CallbackUrlCache.
     *
     * @param callbackUrlCache The CallbackUrlCache instance for storing callbackUrls.
     */
    @Autowired
    public SubscriptionResourceEventHandler(CallbackUrlCache callbackUrlCache) {
        this.callbackUrlCache = callbackUrlCache;
    }

    /**
     * Handles the event when a SubscriptionResource is added.
     *
     * @param resource The SubscriptionResource that was added.
     */
    @Override
    public void onAdd(SubscriptionResource resource) {
        log.debug("Add: {}", resource);

        var subscription = resource.getSpec().getSubscription();
        if (subscription.getDeliveryType().equals(DeliveryType.CALLBACK.getValue())) {
            callbackUrlCache.add(
                    subscription.getSubscriptionId(),
                    new CallbackCacheProperties(subscription.getCallback(), subscription.isCircuitBreakerOptOut())
            );
        }
    }

    /**
     * Handles the event when a SubscriptionResource is updated.
     *
     * @param oldResource The old state of the SubscriptionResource.
     * @param newResource The updated state of the SubscriptionResource.
     */
    @Override
    public void onUpdate(SubscriptionResource oldResource, SubscriptionResource newResource) {
        log.debug("Update: {}", newResource);

        var subscription = newResource.getSpec().getSubscription();
        if (subscription.getDeliveryType().equals(DeliveryType.CALLBACK.getValue())) {
            callbackUrlCache.add(subscription.getSubscriptionId(), new CallbackCacheProperties(subscription.getCallback(), subscription.isCircuitBreakerOptOut()));
        } else {
            callbackUrlCache.remove(subscription.getSubscriptionId());
        }
    }

    /**
     * Handles the event when a SubscriptionResource is deleted.
     *
     * @param resource The SubscriptionResource that was deleted.
     * @param deletedFinalStateUnknown A boolean indicating whether the final state of the deleted resource is unknown.
     */
    @Override
    public void onDelete(SubscriptionResource resource, boolean deletedFinalStateUnknown) {
        log.debug("Delete: {}", resource);

        var subscription = resource.getSpec().getSubscription();
        if (subscription.getDeliveryType().equals(DeliveryType.CALLBACK.getValue())) {
            callbackUrlCache.remove(subscription.getSubscriptionId());
        }
    }

    /**
    * Adds all SubscriptionResources in the given list.
    *
    * @param list The list of SubscriptionResources to add.
    * @param <T>  Type parameter extending HasMetadata.
    */
    @Override
    public <T extends HasMetadata> void addAll(List<T> list) {
        list.forEach(item -> onAdd((SubscriptionResource) item));
    }
}
