# Node/JDK/Maven版本与当前工程一致。构建上下文由.dockerignore排除凭据与私人资料。
FROM node:24-alpine@sha256:8a32c8cf649a49915a66e81e7a609223c708d0adabf288ee3f4077d765542f85 AS web
WORKDIR /build/web
COPY ray-agent-web/package.json ray-agent-web/package-lock.json ./
RUN npm ci
COPY ray-agent-web/index.html ray-agent-web/vite.config.js ./
COPY ray-agent-web/src ./src
RUN npm test && npm run build

FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS backend
WORKDIR /build
COPY pom.xml ./
COPY ray-agent-runtime ./ray-agent-runtime
COPY ray-agent-server ./ray-agent-server
COPY --from=web /build/web/dist ./ray-agent-server/target/generated-web
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

FROM eclipse-temurin:17-jre-alpine@sha256:c4a33e6ddb738fe4be0a28a613ea0940cec42d25d7263c3e2238c43354cb19b0
WORKDIR /app
RUN mkdir -p /app/.ray-data /app/logs /app/config && chown -R 10001:10001 /app
COPY --from=backend /build/ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar /app/server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/server.jar"]
