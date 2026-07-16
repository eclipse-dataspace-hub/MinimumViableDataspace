# -buster is required to have apt available
FROM eclipse-temurin:23_37-jre-alpine

# Optional JVM arguments, such as memory settings
ARG JVM_ARGS=""
ARG JAR

# gcompat provides the glibc dynamic loader required by Bouncy Castle LTS' native probe on musl-based Alpine
RUN apk --no-cache add curl gcompat

WORKDIR /app


COPY ${JAR} issuerservice.jar

EXPOSE 8188

HEALTHCHECK --interval=5s --timeout=5s --retries=10 CMD curl --fail http://localhost:8080/api/check/health

# Use "exec" for graceful termination (SIGINT) to reach JVM.
# ARG can not be used in ENTRYPOINT so storing value in an ENV variable
ENV ENV_JVM_ARGS=$JVM_ARGS
ENTRYPOINT [ "sh", "-c", "exec java $ENV_JVM_ARGS -jar issuerservice.jar --log-level=debug"]