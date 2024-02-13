package de.telekom.horizon.comet.service;

import brave.Span;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.victorialog.model.Observation;
import de.telekom.horizon.comet.model.DeliveryResult;
import lombok.Getter;

public interface DeliveryResultListener {

    void handleDeliveryResult(DeliveryResult deliveryResult);
}
