// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.model;

import brave.Span;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.victorialog.model.Observation;

public record DeliveryResult (
            SubscriptionEventMessage subscriptionEventMessage,
            Observation observation,
            Status status,
            boolean shouldRedeliver,
            Exception exception,
            Span deliverySpan,
            HorizonComponentId messageSource) {}
