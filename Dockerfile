ARG BUILD_FROM=clojure:latest
ARG RUN_FROM=eclipse-temurin:8u422-b05-jre-noble

FROM ${BUILD_FROM} AS builder

RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app

COPY . /usr/src/app
RUN clojure -T:build uber && \
    mv target/emqx-snowflake-proxy-*-standalone.jar app-standalone.jar

FROM ${RUN_FROM} AS runner

COPY --from=builder /usr/src/app/app-standalone.jar /opt/proxy/app-standalone.jar

WORKDIR /opt/proxy

CMD ["java", "-jar", "app-standalone.jar"]
