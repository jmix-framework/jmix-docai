FROM amazoncorretto:21-alpine

# the JVM writes no heap dump into a folder that is not there; a volume mounted there takes its place
RUN mkdir -p /app/heap-dumps

COPY build/libs/jmix-docai.jar jmix-docai.jar

# out of memory, the JVM writes the heap dump and exits, so the restart policy brings the application back clean
ENTRYPOINT ["java", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/heap-dumps", \
            "-XX:+ExitOnOutOfMemoryError", "-jar", "/jmix-docai.jar"]
