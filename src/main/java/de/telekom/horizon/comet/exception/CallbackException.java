// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.exception;

import lombok.Getter;

@Getter
public class CallbackException extends Exception {

    private final int statusCode;


    public CallbackException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
}
