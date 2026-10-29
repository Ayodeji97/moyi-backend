# syntax=docker/dockerfile:1
#
# The one image Moyi runs (ADR-0052). Both Kamal roles start it: `web`, the API,
# with media processing off, and `media-worker`, which decodes uploads with
# libvips and ffmpeg. A Kamal role runs its service's one image, so the
# programs the worker needs are in the image the API runs too; the API never
# starts them (MOYI_MEDIA_PROCESSING_PROVIDER "off", config/deploy.yml).
#
# Built from the boot jar, never from source: the build context is the
# directory that holds it.
#
#   ./gradlew :app:bootJar
#   docker build -f app/Dockerfile -t moyi:local app/build/libs
#
# CI builds it natively on an amd64 and an arm64 runner (.github/workflows/ci.yml),
# as it built the buildpack image before.

# Ubuntu 24.04, by digest: the multi-arch index (linux/amd64, linux/arm64) of
# docker.io/library/ubuntu:24.04, read 2026-10-29. The packages below are
# Ubuntu's noble ones (libvips 8.15, ffmpeg 6.1), which is what the pipelines'
# options were checked against (ADR-0048, ADR-0049) and what CI's tests run.
ARG UBUNTU=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852

# ---------------------------------------------------------------------------
# The JRE: Eclipse Temurin's, from Adoptium's release, its checksum checked.
# Pinned here rather than taken from the eclipse-temurin image, whose 25 tag
# was still 25.0.4.1 on 2026-10-29, a security update behind the 25.0.5 the
# buildpack image was running. A newer JRE arrives as a reviewed change to
# these four lines (https://api.adoptium.net/v3/assets/latest/25/hotspot).
FROM ${UBUNTU} AS jre
ARG TARGETARCH
ARG JRE_RELEASE=jdk-25.0.5%2B8
ARG JRE_FILE_VERSION=25.0.5_8
ARG JRE_SHA256_AMD64=6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6
ARG JRE_SHA256_ARM64=963931723891302ebb2687178532c3d793d71e2577cd058061c7efb396140a86
RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) arch=x64; sha="${JRE_SHA256_AMD64}" ;; \
      arm64) arch=aarch64; sha="${JRE_SHA256_ARM64}" ;; \
      *) echo "no JRE for ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    curl -fsSL -o /tmp/jre.tar.gz \
      "https://github.com/adoptium/temurin25-binaries/releases/download/${JRE_RELEASE}/OpenJDK25U-jre_${arch}_linux_hotspot_${JRE_FILE_VERSION}.tar.gz"; \
    echo "${sha}  /tmp/jre.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/java; \
    tar -xzf /tmp/jre.tar.gz -C /opt/java --strip-components=1; \
    /opt/java/bin/java -version

# ---------------------------------------------------------------------------
# The jar, split into Spring Boot's layers, so that a release that changes only
# the application's own classes changes only the last layer.
FROM ${UBUNTU} AS layers
COPY --from=jre /opt/java /opt/java
WORKDIR /build
COPY *.jar app.jar
RUN /opt/java/bin/java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
FROM ${UBUNTU}

# libvips' command-line tools, the libde265 plugin that lets libheif decode a
# HEIC, and ffmpeg with libopus (and the AMR decoders): exactly what CI's tests
# install. --no-install-recommends keeps out what the packages only suggest.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends libvips-tools libheif-plugin-libde265 ffmpeg; \
    rm -rf /var/lib/apt/lists/*; \
    vips --version; \
    ffmpeg -hide_banner -version | head -n 1

# Never root. No home directory and no login shell: nothing here needs either,
# and the worker's root file system is read-only (config/deploy.yml).
RUN groupadd --system --gid 10001 moyi && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin moyi

ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:$PATH
COPY --from=jre /opt/java /opt/java

WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./

# Kamal refuses to start an image without its service's label
# (config/deploy.yml `service:`). Rename the service and this changes with it.
LABEL service=moyi

USER moyi:moyi

# The API's port and the actuator's (application.yml). The worker listens on
# neither: it runs without a web server (application-media-worker.yml).
EXPOSE 8080 8081

# How much memory the JVM takes is the role's to say (JAVA_TOOL_OPTIONS in
# config/deploy.yml), since one image runs in a 2 GB container and a 768 MB
# one. This default only applies where nothing is said, e.g. a `docker run` by hand.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
