// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.exception;

public class BadTokenResponseException extends RuntimeException {

    private static final String ERROR_MSG_FORMAT = "Error of converting json to map: %s";

    private BadTokenResponseException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public static BadTokenResponseException of(final String responseAsString, final Exception cause) {
        final var message = String.format(ERROR_MSG_FORMAT, responseAsString);
        return new BadTokenResponseException(message, cause);
    }
}
