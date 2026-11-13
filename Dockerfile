# Copyright 2024 Deutsche Telekom IT GmbH
#
# SPDX-License-Identifier: Apache-2.0
ARG DOCKER_BASE_IMAGE=azul/zulu-openjdk-alpine:21-jdk
FROM ${DOCKER_BASE_IMAGE}

WORKDIR app

RUN apk add --no-cache --update gcompat --repository=https://dl-cdn.alpinelinux.org/alpine/v3.18/main/
COPY build/libs/comet.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
