// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.exception;

public class MissingAcknowledgementException extends Exception {
    public MissingAcknowledgementException(String message) {
        super(message);
    }
}
