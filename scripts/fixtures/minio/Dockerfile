# Test fixtures only. Build the same MinIO releases previously used by the gates
# from upstream source: their prebuilt images and downloads are no longer public.
# Keep source archives, the Go toolchain, and the runtime pinned independently.
FROM --platform=$BUILDPLATFORM golang:1.24.2-bookworm@sha256:79390b5e5af9ee6e7b1173ee3eac7fadf6751a545297672916b59bfa0ecf6f71 AS toolchain
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-mod=readonly GOMAXPROCS=2

FROM toolchain AS minio-build
WORKDIR /src
RUN curl --fail --location --retry 3 \
        https://codeload.github.com/minio/minio/tar.gz/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e \
        --output /tmp/source.tar.gz \
    && echo '7eb30a913fea30f18069abf194e1e78e4983b558cc526911ae1c11396a9859a5  /tmp/source.tar.gz' | sha256sum --check \
    && tar --extract --gzip --file /tmp/source.tar.gz --strip-components=1 \
    && rm /tmp/source.tar.gz \
    && go mod download \
    && go mod verify
ARG TARGETARCH
RUN GOOS=linux GOARCH=$TARGETARCH go build -p 2 -trimpath -buildvcs=false \
    -ldflags '-s -w -X github.com/minio/minio/cmd.Version=2025-04-22T22:12:26Z -X github.com/minio/minio/cmd.ReleaseTag=DEVELOPMENT.2025-04-22T22-12-26Z -X github.com/minio/minio/cmd.CommitID=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e -X github.com/minio/minio/cmd.ShortCommitID=0d7408fc9969 -X github.com/minio/minio/cmd.CopyrightYear=2025' \
    -o /out/minio .

FROM toolchain AS mc-build
WORKDIR /src
RUN curl --fail --location --retry 3 \
        https://codeload.github.com/minio/mc/tar.gz/b00526b153a31b36767991a4f5ce2cced435ee8e \
        --output /tmp/source.tar.gz \
    && echo '4cd13e34daeeb8481c3ba8686b082f161b8dc1f7aad52d715a706a587349c6ae  /tmp/source.tar.gz' | sha256sum --check \
    && tar --extract --gzip --file /tmp/source.tar.gz --strip-components=1 \
    && rm /tmp/source.tar.gz \
    && go mod download \
    && go mod verify
ARG TARGETARCH
RUN GOOS=linux GOARCH=$TARGETARCH go build -p 2 -trimpath -buildvcs=false \
    -ldflags '-s -w -X github.com/minio/mc/cmd.Version=2025-04-16T18:13:26Z -X github.com/minio/mc/cmd.ReleaseTag=DEVELOPMENT.2025-04-16T18-13-26Z -X github.com/minio/mc/cmd.CommitID=b00526b153a31b36767991a4f5ce2cced435ee8e -X github.com/minio/mc/cmd.ShortCommitID=b00526b153a31 -X github.com/minio/mc/cmd.CopyrightYear=2025' \
    -o /out/mc .

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8 AS runtime
COPY --from=toolchain /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

FROM runtime AS minio
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
    org.opencontainers.image.revision="0d7408fc9969caf07de6a8c3a84f9fbb10a6739e" \
    org.opencontainers.image.licenses="AGPL-3.0-or-later" \
    org.opencontainers.image.description="Someday test fixture built from MinIO RELEASE.2025-04-22T22-12-26Z source"
COPY --from=minio-build /src/LICENSE /src/CREDITS /licenses/
COPY --from=minio-build /out/minio /usr/bin/minio
ENV MINIO_UPDATE=off
EXPOSE 9000
ENTRYPOINT ["minio"]

FROM runtime AS mc
LABEL org.opencontainers.image.source="https://github.com/minio/mc" \
    org.opencontainers.image.revision="b00526b153a31b36767991a4f5ce2cced435ee8e" \
    org.opencontainers.image.licenses="AGPL-3.0-or-later" \
    org.opencontainers.image.description="Someday test fixture built from mc RELEASE.2025-04-16T18-13-26Z source"
COPY --from=mc-build /src/LICENSE /src/CREDITS /licenses/
COPY --from=mc-build /out/mc /usr/bin/mc
ENTRYPOINT ["mc"]
