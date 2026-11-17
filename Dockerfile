# Runs the packaged Sanctity distribution: the same ZIP docs/service-distribution.md describes.
#
# The build context is an output directory of scripts/package-service.py, which holds exactly
# one sanctity-VERSION.zip and its .sha256. Nothing is compiled here and no source is copied.
#
#   python3 scripts/package-service.py --output target/service-release
#   docker build -f Dockerfile -t sanctity:local target/service-release
#
# scripts/demo-instance.py does both and starts the result with compose.yaml.

# Alpine 3.22: unpacks and verifies the archive only; none of it reaches the runtime image.
FROM alpine@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f AS unpack
WORKDIR /release
COPY . /release/
RUN set -eu; \
    test "$(ls sanctity-*.zip | wc -l)" -eq 1; \
    sha256sum -c sanctity-*.zip.sha256; \
    mkdir /unpacked; \
    unzip -q sanctity-*.zip -d /unpacked; \
    mv /unpacked/sanctity-* /opt/sanctity; \
    chmod 755 /opt/sanctity/bin/sanctity

# Eclipse Temurin 21 JRE on Ubuntu 24.04, the Java and operating system the hosted checks use.
FROM eclipse-temurin@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515
# An ordinary account owns the blob root, mode 0700, as startup requires. A named volume
# mounted here for the first time inherits that owner and mode.
RUN groupadd --system --gid 10001 sanctity \
 && useradd --system --uid 10001 --gid sanctity --create-home --home-dir /home/sanctity --shell /usr/sbin/nologin sanctity \
 && install -d -o sanctity -g sanctity -m 0700 /var/lib/sanctity/blobs
COPY --from=unpack /opt/sanctity /opt/sanctity
USER sanctity
WORKDIR /home/sanctity
ENV SANCTITY_STORAGE=/var/lib/sanctity/blobs
VOLUME ["/var/lib/sanctity/blobs"]
# Every command of the distribution: serve (default), migrate, repository, organization ...
ENTRYPOINT ["/opt/sanctity/bin/sanctity"]
CMD ["serve"]
