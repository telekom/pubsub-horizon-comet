// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.cache;

/** Signals that a subscription could not be read; handled like cache read failures before the local cache. */
public class SubscriptionLookupException extends RuntimeException {

    public SubscriptionLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
