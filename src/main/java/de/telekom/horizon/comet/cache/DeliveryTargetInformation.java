// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.cache;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The {@code DeliveryTargetInformation} class represents properties associated with callbackUrls, including the url
 * itself and an indicator to active or deactivate the circuit breaker.
 */
@Getter
@Setter
@AllArgsConstructor
public class DeliveryTargetInformation {

    /**
     * The url to be called when a callback is triggered.
     */
    private String url;


    /**
     * A boolean flag indicating whether the associated callback should active or deactivate the circuit breaker.
     */
    private boolean optOutCircuitBreaker;

    /**
     * A list of status codes that should be retried.
     */
    private List<Integer> retryableStatusCodes;

}
