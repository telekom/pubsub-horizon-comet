package de.telekom.horizon.comet.exception;

public class TokenRequestErrorException extends RuntimeException {
    private static final String ERROR_MSG_FORMAT = "Can't receive access token. Response: %s";

    private TokenRequestErrorException(final String message) {
        super(message);
    }

    public static TokenRequestErrorException of(final String responseAsString) {
        final var message = String.format(ERROR_MSG_FORMAT, responseAsString);
        return new TokenRequestErrorException(message);
    }
}
