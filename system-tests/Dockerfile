FROM gcr.io/distroless/java21-debian12:nonroot

ENV TZ="Europe/Oslo"
ENV JAVA_OPTS='-XX:MaxRAMPercentage=90'

COPY system-tests/system-tests/target/*jar-with-dependencies.jar /app/app.jar

WORKDIR /app

CMD ["app.jar"]
