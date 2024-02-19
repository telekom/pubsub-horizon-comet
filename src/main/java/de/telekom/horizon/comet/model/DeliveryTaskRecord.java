// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.model;

import brave.Span;
import brave.internal.Nullable;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.horizon.comet.service.DeliveryResultListener;
import de.telekom.horizon.comet.service.DeliveryTaskFactory;
import org.springframework.context.ApplicationContext;

public record DeliveryTaskRecord (
        SubscriptionEventMessage subscriptionEventMessage,
        String callbackUrl,
        long backoffInterval,
        int retryCount,
        DeliveryResultListener deliveryResultListener,
        DeliveryTaskFactory deliveryTaskFactory,
        @Nullable Span deliverySpan,
        HorizonComponentId messageSource,
        ApplicationContext context
) {
}

