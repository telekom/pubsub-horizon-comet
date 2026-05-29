# Copyright 2024 Deutsche Telekom IT GmbH
#
# SPDX-License-Identifier: Apache-2.0
FROM amazoncorretto:21-alpine

WORKDIR app

RUN apk add --no-cache --update gcompat --repository=https://dl-cdn.alpinelinux.org/alpine/v3.18/main/
COPY build/libs/comet.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
