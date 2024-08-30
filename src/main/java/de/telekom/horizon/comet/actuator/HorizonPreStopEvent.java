package de.telekom.horizon.comet.actuator;

import org.springframework.context.ApplicationEvent;

public class HorizonPreStopEvent extends ApplicationEvent {
    private String message;

    public HorizonPreStopEvent(Object source, String message) {
        super(source);
        this.message = message;
    }
    public String getMessage() {
        return message;
    }
}