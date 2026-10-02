# The Spellinator lobby server.
#
# This only packages; it does not build. CI (.github/workflows/spell-server.yml) runs
#
#     ./gradlew :spell-server:installDist
#
# and builds this with spell-server/ as the context. Building the jar in here instead would
# mean configuring the whole Gradle project, Android app included, inside the image.
#
# The word list is NOT in the image -- it is HarperCollins' copyright and the image is
# public. It is mounted read-only at /data; see docs/SPELLINATOR.md.
FROM eclipse-temurin:17-jre-alpine

# A user of its own, never root. TrueNAS runs it as its apps user (568) instead, which
# works just as well: nothing in the image needs writing.
RUN addgroup -S -g 10001 spell && adduser -S -D -H -u 10001 -G spell spell

COPY build/install/spell-server /app

USER 10001:10001
ENV SPELL_PORT=8080 \
    SPELL_DICTIONARY=/data/csw24.txt
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/app/bin/spell-server"]
