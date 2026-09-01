# Stage0: 共通のイメージ
FROM eclipse-temurin:21-jdk-noble AS base

WORKDIR /app

# 開発用 ================

# Stage1: ツールのインストールと開発鯖起動
FROM base AS development

# 開発コンテナにリポジトリ全体をマウントするために変更
WORKDIR /workspace/server

ARG HOST_UID=1000
ARG HOST_GID=1000
COPY development-user.sh /usr/local/bin/development-user
RUN /bin/sh /usr/local/bin/development-user

RUN chown -R ${HOST_UID}:${HOST_GID} /workspace/server
USER ${HOST_UID}

ENV GRADLE_USER_HOME=/home/app/.gradle
ENV JETBRAINS_CACHE_HOME=/home/app/.cache/JetBrains
ENV JETBRAINS_CONFIG_HOME=/home/app/.config/JetBrains
ENV JETBRAINS_SHARE_HOME=/home/app/.local/JetBrains
ENV BUILD_DIR=/workspace/server/build
# NOTE: 各種モジュールのbuildディレクトリを追加
# NOTE: モジュールが増えるたびにここに追加
# NOTE: シングルモジュールの場合不要
RUN mkdir -p  \
    "${GRADLE_USER_HOME}" \
    "${JETBRAINS_CACHE_HOME}" "${JETBRAINS_CONFIG_HOME}" "${JETBRAINS_SHARE_HOME}" \
    "${BUILD_DIR}" \
    /home/app/.vscode-server \
    ./app/build \
    ./contexts/congestion/build \
    ./contexts/destination/build \
    ./contexts/event/build \
    ./contexts/notification/build \
    ./contexts/user/build \
    ./konsist/build \
    ./readmodel/build \
    ./shared/infra/build \
    ./shared/kernel/build

COPY --chown=${HOST_UID}:${HOST_GID} gradlew ./
COPY --chown=${HOST_UID}:${HOST_GID} gradle/ ./gradle/
COPY --chown=${HOST_UID}:${HOST_GID} settings.gradle.kts gradle.properties ./
COPY --chown=${HOST_UID}:${HOST_GID} build.gradle.kts ./
RUN chmod +x gradlew

# NOTE: 各種モジュールのbuild.gradle.kts
# NOTE: モジュールが増えるたびにここに追加
# NOTE: シングルモジュールの場合不要
COPY --chown=${HOST_UID}:${HOST_GID} ./app/build.gradle.kts ./app/
COPY --chown=${HOST_UID}:${HOST_GID} ./contexts/congestion/build.gradle.kts ./contexts/congestion/
COPY --chown=${HOST_UID}:${HOST_GID} ./contexts/destination/build.gradle.kts ./contexts/destination/
COPY --chown=${HOST_UID}:${HOST_GID} ./contexts/event/build.gradle.kts ./contexts/event/
COPY --chown=${HOST_UID}:${HOST_GID} ./contexts/notification/build.gradle.kts ./contexts/notification/
COPY --chown=${HOST_UID}:${HOST_GID} ./contexts/user/build.gradle.kts ./contexts/user/
COPY --chown=${HOST_UID}:${HOST_GID} ./konsist/build.gradle.kts ./konsist/
COPY --chown=${HOST_UID}:${HOST_GID} ./readmodel/build.gradle.kts ./readmodel/
COPY --chown=${HOST_UID}:${HOST_GID} ./shared/infra/build.gradle.kts ./shared/infra/
COPY --chown=${HOST_UID}:${HOST_GID} ./shared/kernel/build.gradle.kts ./shared/kernel/


# NOTE: 各種モジュールの依存を解決する
# NOTE: :app:はマルチモジュールにおける解決対象の各種モジュール
# NOTE: シングルモジュールでは選択する必要はなく、`:modulename:dependencies` -> `dependencies`
# NOTE: モジュールが増えるたびにここに追加
RUN ./gradlew \
    resolveAllDependencies \
    --no-daemon --quiet

# JDWP
EXPOSE 8080 5005

# JVMを用いた開発は動作の重さ的にもコンテナプロセスでホットリロードを動かすのは微妙という結論によりコメントアウト
# COPY --chown=${HOST_UID}:${HOST_GID} ./dev-hot.sh /usr/local/bin/dev-hot.sh
# RUN chmod +x /usr/local/bin/dev-hot.sh
# CMD ["dev-hot.sh"]

CMD [ "sleep", "infinity" ]

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
