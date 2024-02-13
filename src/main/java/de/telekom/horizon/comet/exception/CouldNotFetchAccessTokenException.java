package de.telekom.horizon.comet.exception;

public class CouldNotFetchAccessTokenException extends Exception {

    public CouldNotFetchAccessTokenException(final Exception exception) {
        super(exception);
    }
}
