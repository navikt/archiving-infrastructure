FROM ghcr.io/navikt/baseimages/temurin:17

ENV APPLICATION_NAME=innsending-system-tests
COPY system-tests/system-tests/target/*jar-with-dependencies.jar app.jar

CMD java -jar app.jar
