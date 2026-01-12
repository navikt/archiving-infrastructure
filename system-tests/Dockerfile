FROM gcr.io/distroless/java21-debian12:nonroot


ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=90"
ENV LANG='nb_NO.UTF-8' LANGUAGE='nb_NO:nb' LC_ALL='nb:NO.UTF-8' TZ="Europe/Oslo"

COPY system-tests/system-tests/target/*jar-with-dependencies.jar /app/app.jar

WORKDIR /app

CMD ["app.jar"]
