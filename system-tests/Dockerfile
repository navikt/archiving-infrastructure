FROM gcr.io/distroless/java21-debian12:nonroot

ENV APPLICATION_NAME=innsending-system-tests
COPY system-tests/system-tests/target/*jar-with-dependencies.jar app.jar

CMD java -jar app.jar
