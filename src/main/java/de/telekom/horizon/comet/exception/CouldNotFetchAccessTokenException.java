// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.exception;

public class CouldNotFetchAccessTokenException extends Exception {

    public CouldNotFetchAccessTokenException(final Exception exception) {
        super(exception);
    }
}
