package de.telekom.horizon.comet.client;

import org.springframework.context.ApplicationEvent;

public class TokenFetchingFailureEvent extends ApplicationEvent {
    private String message;

    public TokenFetchingFailureEvent(Object source, String message) {
        super(source);
        this.message = message;
    }
    public String getMessage() {
        return message;
    }
}