<!--
Copyright 2024 Deutsche Telekom IT GmbH

SPDX-License-Identifier: Apache-2.0
-->

# Environment variables

Comet is configured using environment variables. The following environment variables are supported:

| Name | Default | Description |
|---|---|---|
| LOG_LEVEL | WARN | Specifies the logging level for general application logs |
| HORIZON_LOG_LEVEL | WARN | Specifies the logging level for Horizon-related logs |
| JAEGER_COLLECTOR_URL | http://jaeger-collector.example.com:9411 | The URL endpoint for the Jaeger collector, which is used for distributed tracing |
| ZIPKIN_SAMPLER_PROBABILITY | 1.0 | Configures the probability of a trace being sampled for Zipkin. A value of 1.0 means all traces are sampled, while 0.0 means no traces are sampled |
| COMET_INFORMER_NAMESPACE | default | The Kubernetes namespace from which the EventSubscription CRD is being polled |
| IRIS_TOKEN_ENDPOINT | https://iris.example.com/auth/realms/default/protocol/openid-connect/token | The issuer(s) that are trusted by Comet |
| IRIS_CLIENT_ID | foo | Represents a client ID, possibly for authentication |
| IRIS_CLIENT_SECRET | bar | Corresponds to a client secret that is used for authentication |
| COMET_KAFKA_BROKERS | kafka:9092 | Indicates that the Kafka brokers are expected to be available at the address 'kafka' on port '9092' |
| COMET_KAFKA_GROUP_ID | comet | Identifies the Kafka consumer group as comet |
| COMET_KAFKA_PARTITION_COUNT | 4 | Indicates the number of partitions in the Kafka topic |
| COMET_KAFKA_CONSUMER_THREADPOOL_SIZE | 512 | Defines the size of the consumer thread pool |
| COMET_KAFKA_CONSUMER_QUEUE_CAPACITY | 1024 | Sets the capacity of the consumer queue |
| COMET_KAFKA_MAX_POLL_RECORDS | 512 | Specifies the maximum number of records to be polled in a single request |
| COMET_KAFKA_ACKS | 1 | How often the events needs to be acknowledge by Kafka |
| COMET_KAFKA_LINGER_MS | 5 | How long the Kafka waits for other records before transmissing the batch ([Reference](https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#linger-ms)) |
| COMET_KAFKA_COMPRESSION_ENABLED | true | If events send to Kafka should be compressed |
| COMET_KAFKA_COMPRESSION_TYPE | snappy | The compression type used to compress events |
| COMET_MAX_TIMEOUT | 30000 | Maximum timeout duration in milliseconds |
| COMET_MAX_RETRIES | 10 | Maximum number of retry attempts for failed operations |
| COMET_INITIAL_BACKOFF_INTERVAL_MS | 100 | Initial backoff interval duration (in milliseconds) for retry attempts |
| COMET_MAX_BACKOFF_INTERVAL_MS | 60000 | Maximum backoff interval duration (in milliseconds) for retry attempts |
| COMET_BACKOFF_MULTIPLIER | 2.0 | Multiplier applied to the backoff interval between retries |
| COMET_MAX_CONNECTIONS | 1000 | Maximum number of connections allowed |
| COMET_SUCCESSFUL_STATUS_CODES | 200,201,202,204 | HTTP status codes considered as successful responses |
| COMET_REDELIVERY_STATUS_CODES | 401,429,502,503,504 | HTTP status codes triggering message redelivery |
| COMET_REDELIVERY_THREADPOOL_SIZE | 100 | Size of the thread pool for handling message redelivery |
| COMET_REDELIVERY_QUEUE_CAPACITY | 16 | Capacity of the queue for handling message redelivery |
| COMET_RETRIEVE_TOKEN_CONNECT_TIMEOUT | 5000 | Timeout for connecting when retrieving authentication tokens (in milliseconds) |
| COMET_RETRIEVE_TOKEN_READ_TIMEOUT | 5000 | Timeout for reading when retrieving authentication tokens (in milliseconds) |
| COMET_CACHE_SERVICE_DNS | app-cache-headless.example.svc.cluster.local | DNS address for the cache service |
| COMET_CACHE_DE_DUPLICATION_ENABLED | true | If true, enables cache de-duplication |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ENABLED | true | Enables the pod-local subscription cache |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_FALLBACK_MODE | hazelcast-with-mongo-fallback | Read fallback when the local cache cannot serve reads (`hazelcast-with-mongo-fallback` or `none`). With `none`, stale local entries are served indefinitely if necessary |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_MONGO_HEAD_FALLBACK_ENABLED | true | Uses the MongoDB head when the ZooKeeper head cannot be determined (ZooKeeper mode only) |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_SNAPSHOT_COLLECTION | subscriptions.subscriber.horizon.telekom.de.v1-snapshots | MongoDB collection with the snapshot entries |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_HEAD_COLLECTION | subscriptions.subscriber.horizon.telekom.de.v1-head | MongoDB collection with the head of the active snapshot |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_STALE_LOCAL_CACHE_READ_GRACE_PERIOD | 120s | How long a stale local snapshot may serve reads before Hazelcast is used. Only applies to `FALLBACK_MODE=hazelcast-with-mongo-fallback` |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_REQUIRE_LOCAL_CACHE_AT_STARTUP | true | Whether startup waits for the first local snapshot. Only applies to `FALLBACK_MODE=hazelcast-with-mongo-fallback`; with `none`, startup always waits |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_INITIAL_SNAPSHOT_TIMEOUT | 120s | Maximum wait for the first local snapshot when startup waits for it; afterwards startup fails and the process terminates. `0s` waits indefinitely |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_RECONCILE_INTERVAL | 60s | Interval for re-checking the active head (ZooKeeper or MongoDB); `0s` disables it |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_MONGO_HEAD_POLL_JITTER | 10s | Maximum random offset of the first periodic head reconciliation |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_MONGO_SNAPSHOT_SYNC_JITTER | 10s | Maximum random delay before loading a snapshot for prepared preloads and reconnects (ZooKeeper mode only) |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_ENABLED | true | ZooKeeper as head source; `false` polls only the MongoDB head |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_ENSEMBLE_TRACKER_ENABLED | true | Lets Curator follow ZooKeeper-published ensemble addresses. Can be `false` for local operation, because the published addresses are not reachable from the host |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_CONNECT_STRING | (empty) | ZooKeeper connect string; required in ZooKeeper mode |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_PREPARED_PATH | /horizon/subscriptions/prepared | ZNode path of the prepared head |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_ACTIVATE_PATH | /horizon/subscriptions/activated | ZNode path of the activated head |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_CONNECTION_TIMEOUT | 15s | Curator connection timeout |
| COMET_CACHE_LOCAL_SUBSCRIPTION_CACHE_ZOO_KEEPER_SESSION_TIMEOUT | 60s | ZooKeeper session timeout |
