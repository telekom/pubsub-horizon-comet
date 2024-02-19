// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.horizon.comet.model.DeliveryResult;

public interface DeliveryResultListener {

    void handleDeliveryResult(DeliveryResult deliveryResult);
}
