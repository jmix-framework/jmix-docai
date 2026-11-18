FROM amazoncorretto:21-alpine

# the JVM writes no heap dump into a folder that is not there
RUN mkdir -p /app/heap-dumps

COPY build/libs/jmix-docai.jar jmix-docai.jar

ENTRYPOINT ["java", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/heap-dumps", \
            "-XX:+ExitOnOutOfMemoryError", "-jar", "/jmix-docai.jar"]
