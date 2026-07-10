FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app ./app
COPY shared ./shared
COPY contexts ./contexts
COPY readmodel ./readmodel
COPY konsist ./konsist

RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

ENV PORT=8080

COPY --from=build /workspace/app/build/install/app ./

EXPOSE 8080

CMD ["./bin/app"]
