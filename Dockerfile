FROM amazoncorretto:21-alpine

COPY build/libs/jmix-docai.jar jmix-docai.jar

ENTRYPOINT ["java", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/heap-dumps", "-jar", "/jmix-docai.jar"]
