// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;

import java.util.Collection;

/**
 * The {@code KafkaConsumerRebalanceListener} class implements the {@link ConsumerRebalanceListener} interface
 * to handle events related to partition assignment and revocation in a Kafka consumer.
 */

@Slf4j
public class KafkaConsumerRebalanceListener implements ConsumerRebalanceListener {

    /**
     * Called when partitions are assigned to the consumer.
     *
     * @param partitions The collection of TopicPartitions that are assigned.
     */
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            log.info("Partition assigned: {}-{}", partition.topic(), partition.partition());
        }
    }

    /**
     * Called when partitions are revoked from the consumer.
     *
     * @param partitions The collection of TopicPartitions that are revoked.
     */
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            log.info("Partition revoked: {}-{}", partition.topic(), partition.partition());
        }
    }

    /**
     * Called when partitions are lost due to a rebalance.
     *
     * @param partitions The collection of TopicPartitions that are lost.
     */
    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        ConsumerRebalanceListener.super.onPartitionsLost(partitions);
    }
}
