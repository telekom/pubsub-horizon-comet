package de.telekom.horizon.comet.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code AccessToken} class represents an access token used for authentication and authorization.
 * The token has information about its value, issue time, and time to live (expiration time).
 */
public class AccessToken {
    private static final String ACCESS_TOKEN_FIELD = "access_token";
    private static final String EXPIRES_IN_FIELD = "expires_in";
    private static final int BUFFER_TIME_FOR_TOKEN_REFRESH = 5;

    private final String token;
    private final Instant issueTime;
    private final long timeToLive;

    /**
     * Constructor to create an AccessToken instance.
     *
     * @param token The access token value.
     * @param issueTime The time when the token was issued.
     * @param timeToLive The time to live of the token (expiration time).
     */
    private AccessToken(String token, Instant issueTime, long timeToLive) {
        this.token = token;
        this.issueTime = issueTime;
        this.timeToLive = timeToLive;
    }

    /**
     * Creates an AccessToken instance from a response map containing token information.
     *
     * @param responseAsMap The map containing the access token and the expiration time.
     * @return The AccessToken instance.
     */
    public static AccessToken of(final Map<String, Object> responseAsMap) {
        final Object token = responseAsMap.get(ACCESS_TOKEN_FIELD);
        Objects.requireNonNull(token, "Token is null");

        final Object expiresIn = responseAsMap.get(EXPIRES_IN_FIELD);
        Objects.requireNonNull(expiresIn, "Expires in is null");

        final var tokenAsString = token.toString();
        final var expiresInAsLong = Long.parseLong(expiresIn.toString());

        return new AccessToken(tokenAsString, Instant.now(), expiresInAsLong);
    }

    /**
     * Checks if the access token has expired.
     *
     * @return true if the token has expired, false otherwise.
     */
    public boolean isExpired() {
        return issueTime.plus(timeToLive - BUFFER_TIME_FOR_TOKEN_REFRESH, ChronoUnit.SECONDS)
                .compareTo(Instant.now()) <= 0;
    }

    /**
     * Gets the value of the access token.
     *
     * @return The access token value.
     */
    public String getToken() {
        return token;
    }

    /**
     * Overrides the hashCode method to generate a hash code for AccessToken instances.
     *
     * @return The hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(token, issueTime, timeToLive);
    }

    /**
     * Overrides the equals method to compare AccessToken instances.
     *
     * @param object The object to compare with.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final AccessToken that = (AccessToken) object;
        return areFieldsEqual(that);
    }

    /**
     * Checks if the fields of the AccessToken instances are equal.
     *
     * @param that The AccessToken instance to compare with.
     * @return True if the fields are equal, false otherwise.
     */
    private boolean areFieldsEqual(AccessToken that) {
        return timeToLive == that.timeToLive &&
                Objects.equals(token, that.token) &&
                Objects.equals(issueTime, that.issueTime);
    }
}
