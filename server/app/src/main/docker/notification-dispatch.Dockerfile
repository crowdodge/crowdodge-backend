FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /opt/app
COPY --from=build /workspace/app/build/install/app/lib ./lib

ENTRYPOINT ["java", "-cp", "/opt/app/lib/*", "com.crowdodge.app.notification.NotificationDispatchMainKt"]
