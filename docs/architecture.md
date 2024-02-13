<!--
Copyright 2024 Deutsche Telekom IT GmbH

SPDX-License-Identifier: Apache-2.0
-->

# Architecture
The primary function of the Comet component is to facilitate the delivery of subscription-based event messages to subscribers using a callback for the deliverType.
To achieve this, the component is configured to monitor the Kafka topic labeled as subscribed. The activation of the component is triggered by each newly subscribed event message with a deliverType callback.

Subsequently, the Comet component checks whether the CircuitBreaker has the status open or checking.
The CircuitBreaker acts as a mechanism to halt the delivery of events when the endpoint to receive these events is unreachable.
If the CircuitBreaker is in the open or checking state, the events are not forwarded to the endpoint.
Instead, they are marked as WAITING, the status in Kafka is updated with WAITING and the next event will be processed.

A duplication mechanism is then used to check whether the event has already been handled or processed.
If this is the case, the event is not processed further, its status is set to DUPLICATE and the status in Kafka is updated with DUPLICATE.
Otherwise, the Comet component initiates an attempt to deliver the event, marks it with DELIVERING and updates the status in Kafka with DELIVERING.

Then the deliveryTask starts. As it is possible that the CircuitBreaker for an event opens during the start of the deliveryTask, a new check of the CircuitBreaker status is carried out within the deliveryTask
(whether it is open or being checked). If this is confirmed, the status is set to WAITING again, the status in kafka is updated and the next event is processed.

If the CircuitBreaker is closed, an attempt is made to deliver the event. If a 200, 201, 202 or 204 http statusCode is returned, the event is marked as DELIVERED.
In the case of a 401, 429, 502, 503, or 504 statusCode, repeated attempts are made until the maximum retry counts are reached.

Upon reaching the maximum retry counts, the system checks if the CircuitBreaker is activated for the subscription.
If it is activated, the CircuitBreaker is opened, and the event is marked as WAITING. If the CircuitBreaker is not activated, the event is marked as FAILED.

The event details are then recorded in the deduplication cache with status such as WAITING, FAILED or DELIVERED, and the event status is written in Kafka.

Furthermore, similar to all other components in Horizon, the Comet component incorporates logs, tracing, and metrics to document its functionalities and performance metrics.

### Abbreviations
CB -> CircuitBreaker

## Flowchart
```mermaid
flowchart TD
    %% Start of the process
    Start[Listen for Subscribed Event Messages]

    Start --> |For each event message| DecisionDeliveryType{Is event's <br> delivery type <br> callback?}

    %% Check if DeliveryType callback is received
    DecisionDeliveryType --> |Yes| DecisionCircuitBreaker{Is the status of<br>CB <br>open or checking?}
    DecisionDeliveryType --> |No| IGNORE(Ignore event message)

    %% Check CircuitBreaker status
    DecisionCircuitBreaker --> |Yes| WaitingStatusMessage(Send WAITING statusMessage) --> WaitForAllDeliveryTasks

    DecisionCircuitBreaker --> |No| DecisionDuplicate{Is the event <br> a duplicate?} 

    %% Check for Duplicate event
    DecisionDuplicate --> |Yes| DuplicateStatusMessage(Send DUPLICATE statusMessage)  --> WaitForAllDeliveryTasks

    %% DeliveryTask
    DecisionDuplicate --> |No| SendDelivering(Send DELIVERING statusMessage) --> DeliveryTask[DeliveryTask] --> WaitForAllDeliveryTasks
    style DeliveryTask stroke:#FFA500,stroke-width:2px

    WaitForAllDeliveryTasks(Wait for the first statusMessage update for all event messages)

    SendDelivering --> WaitForAllDeliveryTasks

    %% After all StatusMessages are sent
    WaitForAllDeliveryTasks --> WasSuccessful{Were all<br>statusMessages<br>successful send?}
    WasSuccessful --> |Yes| ACKDeliveryTasks(Acknowledge Batch)
    WasSuccessful --> |No| NACKDeliveryTasks(NACK Batch)
```
## Delivery Task
```mermaid
flowchart TD
    subgraph DeliveryTask [Delivery Task]
    style DeliveryTask stroke:#FFA500,stroke-width:2px
        
        Start_DeliveryTask[Start]

        %% Check CircuitBreaker status for Delivery Task
        Start_DeliveryTask --> DecisionCircuitBreaker_DeliveryTask{Is the status <br> of the CB <br> open or checking?}
    
        %% Process based on CircuitBreaker status
        DecisionCircuitBreaker_DeliveryTask --> |Yes| WaitingStatusMessage_DeliveryTask(Send WAITING statusMessage)
        DecisionCircuitBreaker_DeliveryTask --> |No| ExecuteRequest(Send Event via <br>HTTP-Request<br>to consumer)
    
        %% Handle successful request
        ExecuteRequest --> |Successful| DeliveredStatusMessage(Send DELIVERED statusMessage)
        ExecuteRequest --> |Failure| DecisionRetryable{Is the status code<br>retryable?}
    
        %% Check if the request is retryable
        DecisionRetryable --> |No| FailedStatusMessage(Send FAILED statusMessage)
        DecisionRetryable --> |Yes| DecisionRedeliver{Is the retry count<br>reached?}
    
        %% Check if redelivery is needed
        DecisionRedeliver --> |Yes| DecisionCircuitBreakerActive{Is the CB <br> enabled?}
        DecisionRedeliver --> |No| IncrementRetryCount(Increment retryCount)
        IncrementRetryCount --> |Spawn new DeliveryTask| Start_DeliveryTask
    
        %% Check if CircuitBreaker is active for redelivery
        DecisionCircuitBreakerActive --> |No| FailedStatusMessage(Send FAILED statusMessage)
        DecisionCircuitBreakerActive --> |Yes| WaitingStatusMessage_CircuitBreaker(Send WAITING statusMessage) --> OpenCircuitBreaker(Open CB)
    end
```

## State Diagram
````mermaid
stateDiagram
    PROCESSED --> WAITING: CB is open/checking
    PROCESSED --> DUPLICATE: Event is duplicate
    PROCESSED --> DELIVERING: No open/checking CB

    DELIVERING --> DELIVERED: Successful delivery
    DELIVERING --> FAILED: Internal error<br>or CB is disabled
    DELIVERING --> WAITING: CB is open/checking or<br>retry count is reached
````