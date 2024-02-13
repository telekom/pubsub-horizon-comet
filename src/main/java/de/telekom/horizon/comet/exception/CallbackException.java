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
