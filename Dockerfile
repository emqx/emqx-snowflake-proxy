FROM clojure:latest

RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app

COPY . /usr/src/app
RUN clojure -T:build uber && \
    mv target/emqx-snowflake-proxy-*-standalone.jar app-standalone.jar

CMD ["java", "-jar", "app-standalone.jar"]
