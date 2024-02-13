// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.utils;

import de.telekom.eni.pandora.horizon.model.event.MessageType;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;

/**
 * The {@code MessageUtils} class for utility methods related to Kafka messages.
 * This class provides methods for checking if a message is a status message and extracting the message source.
 */
public class MessageUtils {

    /**
     * Checks if the given Kafka record represents a status message.
     *
     * @param record The Kafka ConsumerRecord to check.
     * @return True if the record is a status message; otherwise, false.
     */
    public static boolean isStatusMessage(ConsumerRecord<String, String> record) {
        var bytes = record.headers().lastHeader("type").value();
        var messageType = new String(bytes, StandardCharsets.UTF_8);

        return messageType.equals(MessageType.METADATA.name());
    }

    /**
     * Retrieves the message source from the given Kafka record.
     *
     * @param record The Kafka ConsumerRecord to extract the message source from.
     * @return The HorizonComponentId representing the message source.
     */
    public static HorizonComponentId getMessageSource(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("clientId");
        if (header == null) {
            return HorizonComponentId.UNSET;
        }
        var clientIdString = new String(header.value(), StandardCharsets.UTF_8);
        return HorizonComponentId.fromGroupId(clientIdString);
    }

}
