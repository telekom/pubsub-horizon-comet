<!--
Copyright 2024 Deutsche Telekom IT GmbH

SPDX-License-Identifier: Apache-2.0
-->

<p align="center">
  <img src="docs/img/comet.svg" alt="Comet logo" width="200">
  <h1 align="center">Comet</h1>
</p>

<p align="center">
  Horizon component for the delivery and redelivery of eventMessages with deliveryType callback.
</p>

<p align="center">
  <a href="#prerequisites">Prerequisites</a> •
  <a href="#building-comet">Building Comet</a> •
  <a href="#configuration">Configuration</a> •
  <a href="#running-comet">Running Comet</a>
</p>

<!--
[![REUSE status](https://api.reuse.software/badge/github.com/telekom/pubsub-horizon-comet)](https://api.reuse.software/info/github.com/telekom/pubsub-horizon-comet)
-->
[![Gradle Build and Test](https://github.com/telekom/pubsub-horizon-comet/actions/workflows/gradle-build.yml/badge.svg)](https://github.com/telekom/pubsub-horizon-comet/actions/workflows/gradle-build.yml)

## Overview

Horizon Comet is one of the central components for the delivery of event messages. It handles the delivery and redelivery of event messages to callback endpoints of subscribed consumers and provides retry mechanisms.

> **Note:** Comet is an essential part of the Horizon ecosystem. Please refer to [documentation of the entire system](https://github.com/telekom/pubsub-horizon) to get the full picture.

## Prerequisites
For the optimal setup, ensure you have:

- A running instance of Kafka
- Access to a Kubernetes cluster on which the `Subscription` (subscriber.horizon.telekom.de) custom resource definition has been registered

### Gradle build

```bash
./gradlew build
```

### Docker build

The default docker base image is `azul/zulu-openjdk-alpine:21-jre`. This is customizable via the docker build arg `DOCKER_BASE_IMAGE`.
Please note that the default helm values configure the kafka compression type `snappy` which requires gcompat to be installed in the resulting image.
So either provide a base image with gcompat installed or change/disable the compression type in the helm values.

```bash
docker build -t horizon-comet:latest --build-arg="DOCKER_BASE_IMAGE=<myjvmbaseimage:1.0.0>" . 
```

#### Multi-stage Docker build

To simplify things, we have also added a mult-stage Dockerfile to the respository, which also handles the Java build of the application in a build container. The resulting image already contains "gcompat", which is necessary for Kafka compression.

```bash
docker build -t horizon-comet:latest . -f Dockerfile.multi-stage 
```

## Configuration
Comet configuration is managed through environment variables. Check the [complete list](docs/environment-variables.md) of supported environment variables for setup instructions.

## Running Comet
### Locally
Before you can run Comet locally you must have a running instance of Kafka locally or forwarded from a remote cluster.
Additionally, you need to have a Kubernetes config at `${user.home}/.kube/config.main` that points to the cluster you want to use.

After that you can run Comet in a dev mode using this command:
```shell
./gradlew bootRun
```

## Documentation

Read more about the software architecture and the general process flow of Horizon Comet in [docs/architecture.md](docs/architecture.md).

## Contributing

We're committed to open source, so we welcome and encourage everyone to join its developer community and contribute, whether it's through code or feedback.  
By participating in this project, you agree to abide by its [Code of Conduct](./CODE_OF_CONDUCT.md) at all times.

## Code of Conduct

This project has adopted the [Contributor Covenant](https://www.contributor-covenant.org/) in version 2.1 as our code of conduct. Please see the details in our [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). All contributors must abide by the code of conduct.

By participating in this project, you agree to abide by its [Code of Conduct](./CODE_OF_CONDUCT.md) at all times.

## Licensing

This project follows the [REUSE standard for software licensing](https://reuse.software/). You can find a guide for developers at https://telekom.github.io/reuse-template/.   
Each file contains copyright and license information, and license texts can be found in the [./LICENSES](./LICENSES) folder. For more information visit https://reuse.software/.
