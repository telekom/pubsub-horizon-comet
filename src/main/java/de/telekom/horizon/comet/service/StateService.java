package de.telekom.horizon.comet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.telekom.eni.pandora.commons.tracing.ScopedDebugSpanWrapper;
import de.telekom.eni.pandora.horizon.kafka.event.EventWriter;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.StatusMessage;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import de.telekom.eni.pandora.horizon.victorialog.model.AdditionalFields;
import de.telekom.eni.pandora.horizon.victorialog.model.MetricNames;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The {@code StateService} class for updating the state of subscription events and writing status to Kafka.
 * This service provides methods for updating event states and writing corresponding status messages.
 */
@Slf4j
@Service
public class StateService {
    private final HorizonTracer tracer;
    private final EventWriter eventWriter;

    /**
     * Constructs a StateService with necessary dependencies.
     *
     * @param tracer       The tracer for creating spans.
     * @param eventWriter  The writer for sending events to Kafka.
     */
    @Autowired
    public StateService(HorizonTracer tracer, EventWriter eventWriter) {
        this.tracer = tracer;
        this.eventWriter = eventWriter;
    }

    /**
     * Updates the state by adding traces and writing to Kafka synchronously, waiting for the send method to finish.
     * Important: This method is designed to wait for the starlight to finish the send process, ensuring that the message is
     * inside Kafka. This allows tracking the event in deduplication and metrics.
     *
     * @param status                    The new status to set for the event.
     * @param subscriptionEventMessage The SubscriptionEventMessage to update the state for.
     * @param exception                 The exception that occurred, if any.
     * @return CompletableFuture with SendResult containing the result of the status update.
     */
    public CompletableFuture<SendResult<String, String>> updateState(Status status, SubscriptionEventMessage subscriptionEventMessage, Exception exception) {
        var span = tracer.startScopedDebugSpan("write event status");

        try {
            log.debug("Start writing status {} for event {}", status, subscriptionEventMessage.getUuid());

            addTagsToSpan(span, subscriptionEventMessage, status);

            StatusMessage statusMessage = prepareStatusMessage(subscriptionEventMessage, status, exception);

            return sendEventAsync(subscriptionEventMessage, statusMessage, status);
        } catch (JsonProcessingException e) {
            log.error("Error while writing new event status", e);
            span.error(e);
            throw new RuntimeException(e);
        } catch (Exception unexpectedException) {
            log.error("Unexpected error while writing status {} for event {}", status, subscriptionEventMessage.getUuid(), unexpectedException);
            span.error(unexpectedException);
            throw new RuntimeException(unexpectedException);
        } finally {
            span.finish();
        }
    }

    /**
     * Adds tags to the provided span based on status and subscriptionEventMessage.
     *
     * @param span                     The span to add tags to.
     * @param subscriptionEventMessage The SubscriptionEventMessage to extract information from.
     * @param status                   The status of the event.
     */
    private void addTagsToSpan(ScopedDebugSpanWrapper span, SubscriptionEventMessage subscriptionEventMessage, Status status) {
        tracer.addTagsToSpanFromEventMessage(span, subscriptionEventMessage);
        tracer.addTagsToSpanFromSubscriptionEventMessage(span, subscriptionEventMessage);
        tracer.addTagsToSpan(span, List.of(Pair.of("status", status.name())));
    }


    /**
     * Prepares a status message based on the subscriptionEventMessage, status, and exception.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to extract information from.
     * @param status                   The status of the event.
     * @param exception                The exception that occurred, if any.
     * @return The prepared StatusMessage.
     * @throws JsonProcessingException If there is an error processing the JSON.
     */
    private StatusMessage prepareStatusMessage(SubscriptionEventMessage subscriptionEventMessage, Status status, Exception exception) throws JsonProcessingException {
        StatusMessage statusMessage = new StatusMessage(subscriptionEventMessage.getUuid(), subscriptionEventMessage.getEvent().getId(), status, subscriptionEventMessage.getDeliveryType()).withThrowable(exception);

        if (subscriptionEventMessage.getAdditionalFields() != null) {
            List<String> keyList = Arrays.asList(MetricNames.EndToEndLatencyCustomer.getAsHeaderValue(),
                    MetricNames.EndToEndLatencyTardis.getAsHeaderValue(),
                    AdditionalFields.START_TIME_TRUSTED.getValue());

            Map<String, Object> partialStatusMessageAdditionalFields = subscriptionEventMessage.getAdditionalFields().entrySet().stream()
                    .filter(entry -> keyList.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            statusMessage.setAdditionalFields(partialStatusMessageAdditionalFields);
        }

        return statusMessage;
    }

    /**
     * Sends the event asynchronously and handles exceptions.
     *
     * @param subscriptionEventMessage The SubscriptionEventMessage to send.
     * @param statusMessage            The StatusMessage to send.
     * @param status                   The status of the event.
     * @return CompletableFuture with SendResult containing the result of the asynchronous send.
     * @throws JsonProcessingException If there is an error processing the JSON.
     */
    private CompletableFuture<SendResult<String, String>> sendEventAsync(SubscriptionEventMessage subscriptionEventMessage, StatusMessage statusMessage, Status status) throws JsonProcessingException {
        var afterSendFuture = eventWriter.send(
                Objects.requireNonNullElse(subscriptionEventMessage.getEventRetentionTime(), EventRetentionTime.DEFAULT).getTopic(),
                statusMessage,
                tracer
        );

        // Log and handle exceptions asynchronously
        afterSendFuture.exceptionally(ex -> {
            log.error("Unexpected error while sending status {} for event {}", status, subscriptionEventMessage.getUuid(), ex);
            return null;
        });
        afterSendFuture.thenAccept(result -> log.debug("Finished sending status {} for event {}", status, subscriptionEventMessage.getUuid()));

        return afterSendFuture;
    }
}
