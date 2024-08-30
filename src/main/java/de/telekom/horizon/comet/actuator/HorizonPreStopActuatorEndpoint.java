package de.telekom.horizon.comet.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "horizon-prestop")
public class HorizonPreStopActuatorEndpoint {

    private final ApplicationEventPublisher applicationEventPublisher;

    public HorizonPreStopActuatorEndpoint(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @WriteOperation
    public void handlePreStop() {
        var event = new HorizonPreStopEvent(this, "Got PreStop request. Terminating all connections...");
        applicationEventPublisher.publishEvent(event);
    }
}