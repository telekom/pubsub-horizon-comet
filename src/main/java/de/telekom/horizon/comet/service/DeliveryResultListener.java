// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;
import de.telekom.horizon.comet.model.DeliveryResult;

public interface DeliveryResultListener {

    void handleDeliveryResult(DeliveryResult deliveryResult) throws HorizonException;
}
