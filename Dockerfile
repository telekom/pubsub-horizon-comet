FROM mtr.devops.telekom.de/tardis-internal/pandora/pandora-java-21:1.0.0

WORKDIR app

COPY build/libs/comet.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
