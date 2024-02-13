package de.telekom.horizon.comet.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.telekom.eni.pandora.horizon.model.event.MessageType;
import de.telekom.horizon.comet.service.SubscribedEventMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jetbrains.annotations.NotNull;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.lang.NonNull;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static de.telekom.horizon.comet.utils.MessageUtils.isStatusMessage;

/**
 * The {@code SubscribedEventMessageListener} class implements the {@link BatchAcknowledgingMessageListener} interface
 * to handle subscribed event messages.
 */
@Slf4j
public class SubscribedEventMessageListener extends AbstractConsumerSeekAware implements BatchAcknowledgingMessageListener<String, String> {

    private final SubscribedEventMessageHandler subscribedEventMessageHandler;

    /**
     * Constructor for SubscribedEventMessageListener.
     *
     * @param subscribedEventMessageHandler The SubscribedEventMessageHandler for processing subscribed event messages.
     */
    public SubscribedEventMessageListener(SubscribedEventMessageHandler subscribedEventMessageHandler) {
        super();
        this.subscribedEventMessageHandler = subscribedEventMessageHandler;
    }

    /**
     * Checks the message for METADATA type and starts handling the message.
     *
     * @param record The ConsumerRecord representing the received message.
     * @return A CompletableFuture with the SendResult or null if the message type was METADATA or an exception occurred.
     */
    public CompletableFuture<SendResult<String, String>> onMessage(@NonNull ConsumerRecord<String, String> record) {
       if (isStatusMessage(record)) {
            log.debug("Received (metadata) ({}) record at partition {} and offset {} in topic {} with record id {}", MessageType.METADATA, record.partition(), record.offset(), record.topic(), record.key());
            return null;
        }

        log.debug("Received (message) ({}) record at partition {} and offset {} in topic {} with record id {}", MessageType.MESSAGE, record.partition(), record.offset(), record.topic(), record.key());

        try {
            return subscribedEventMessageHandler.handleMessage(record);
        } catch (JsonProcessingException e) {
            log.error("Could not parse SubscriptionEventMessage. Record {} can not be processed", record);
        } catch (Exception e) {
            log.error("Unexpected error occurred while handle message {}", record.key(), e);
        }

        return null;
    }

    /**
     * Handles a batch of messages and acknowledges or nacks based on the outcome of message handling.
     *
     * @param records       The list of ConsumerRecords representing the received messages.
     * @param acknowledgment The Acknowledgment to acknowledge or nack the batch.
     */
    @Override
    public void onMessage(@NotNull List<ConsumerRecord<String, String>> records, @NotNull Acknowledgment acknowledgment) {
        List<String> eventUuids = records.stream().map(ConsumerRecord::key).toList();
        try {
            var afterDeliveringSendFutures = records.stream().map(this::onMessage).filter(Objects::nonNull).map(CompletableFuture::completedFuture).toList();
            var sendFuturesArray = afterDeliveringSendFutures.toArray(new CompletableFuture[0]);
            log.debug("Create sendFutureArray {} of list {}", sendFuturesArray, afterDeliveringSendFutures);
            CompletableFuture.allOf(sendFuturesArray).join();
        } catch (Exception ex) {
            log.error("Exception thrown while handling events with ids [{}]. Nacking batch.", eventUuids, ex);
            acknowledgment.nack(Duration.of(5000, ChronoUnit.MILLIS));
            return;
        }
        acknowledgment.acknowledge();

        log.debug("Acknowledged record batch from offset {} to offset {} with event ids [{}]", records.get(0).offset(), records.get(records.size()-1).offset(), eventUuids);
    }
}
