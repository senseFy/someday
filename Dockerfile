# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy@sha256:ce5767b7222312d42395f5bab033cd91f09e44032a2f21bdfd7b5b912dbe1e77 AS build

WORKDIR /workspace

COPY --chmod=0755 gradlew ./gradlew
COPY gradle ./gradle
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY server/build.gradle.kts ./server/build.gradle.kts
COPY server/src ./server/src

RUN install -d \
        app/android \
        app/desktop \
        app/ios \
        integration-tests \
        shared/data \
        shared/domain \
        shared/sync \
        shared/ui

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew \
        --no-daemon \
        --configure-on-demand \
        -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" \
        :server:installDist

FROM eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf AS runtime

LABEL org.opencontainers.image.title="Someday Server" \
      org.opencontainers.image.description="Self-hosted synchronization server for Someday" \
      org.opencontainers.image.source="https://github.com/senseFy/someday" \
      org.opencontainers.image.licenses="GPL-3.0-only"

RUN groupadd --gid 10001 someday \
    && useradd \
        --uid 10001 \
        --gid someday \
        --create-home \
        --home-dir /home/someday \
        --shell /usr/sbin/nologin \
        someday \
    && install -d -o someday -g someday -m 0700 /var/lib/someday/media

COPY --from=build --chown=someday:someday /workspace/server/build/install/server /opt/someday
COPY --chmod=0555 docker/entrypoint.sh /opt/someday/bin/docker-entrypoint
COPY --chmod=0555 docker/healthcheck.sh /opt/someday/bin/docker-healthcheck
COPY LICENSE /usr/share/licenses/someday/LICENSE

ENV HOME=/home/someday \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp" \
    SOMEDAY_DEPLOYMENT_MODE=production \
    SOMEDAY_HOST=0.0.0.0 \
    SOMEDAY_PORT=3180

USER 10001:10001
WORKDIR /opt/someday

EXPOSE 3180
STOPSIGNAL SIGTERM

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["/opt/someday/bin/docker-healthcheck"]

ENTRYPOINT ["/opt/someday/bin/docker-entrypoint"]
