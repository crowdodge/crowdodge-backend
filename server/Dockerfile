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
RUN <<'EOF'
set -eu

APP_USER="app"
APP_GROUP="app"

# Group

gname_by_gid=$(getent group "${HOST_GID}" | cut -d: -f1 || true) # HOST_GIDから取得できる（既存の）グループ名
gid_by_tgt_user=$(getent group "${APP_GROUP}" | cut -d: -f3 || true) # 対象グループ名から取得できる（既存の）GID

if [ -z "${gname_by_gid}" ] && [ -z "${gid_by_tgt_user}" ]; then # グループ名もGIDも存在しない
groupadd -g "${HOST_GID}" "${APP_GROUP}"
elif [ "${gname_by_gid}" = "${APP_GROUP}" ]; then # グループ名と対象グループ名が一致
:
elif [ -n "$gid_by_tgt_user" ] && [ "${gid_by_tgt_user}" != "${HOST_GID}" ]; then # グループ名は存在するが、GIDが異なる
groupmod -g "${HOST_GID}" "${APP_GROUP}"
elif [ -n "${gname_by_gid}" ] && [ "${gname_by_gid}" != "${APP_GROUP}" ]; then # GIDは存在するが、グループ名が異なる
groupmod -n "${APP_GROUP}" "${gname_by_gid}"
fi

# User

uname_by_uid=$(getent passwd "${HOST_UID}" | cut -d: -f1 || true ) # HOST_UIDから取得できる（既存の）ユーザ名
uid_by_tgt_user=$(getent passwd "${APP_USER}" | cut -d: -f3 || true) # 対象ユーザ名から取得できる（既存の）UID

if [ -z "${uname_by_uid}" ] && [ -z "${uid_by_tgt_user}" ]; then
useradd -u "${HOST_UID}" -g "${APP_GROUP}" -m -s /bin/bash "${APP_USER}"
elif [ "${uname_by_uid}" = "${APP_USER}" ]; then
:
elif [ -n "${uid_by_tgt_user}" ] && [ "${uid_by_tgt_user}" != "${HOST_UID}" ]; then
usermod -u "${HOST_UID}" "${APP_USER}"
elif [ -n "${uname_by_uid}" ] && [ "${uname_by_uid}" != "${APP_USER}" ]; then
usermod -l "${APP_USER}" -d "/home/${APP_USER}" -m "${uname_by_uid}"
fi
EOF

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

# デプロイ用 ================

# Stage1: ビルド
FROM base AS builder

COPY . .

# NOTE: :app:はマルチモジュールにおけるエンドポイントとなるモジュール名
# NOTE: シングルモジュールでは不要なので、`:modulename:buildFatJar` -> `buildFatJar`
RUN ./gradlew :app:buildFatJar --no-daemon

# Stage2: 軽量イメージでの実行
FROM gcr.io/distroless/java21-debian13:nonroot AS production

WORKDIR /app

# NOTE: `build/`の一つ上の階層である`app/`はマルチモジュールにおけるエンドポイントとなるモジュール名
# NOTE: シングルモジュールでは不要なので、`/app/app/build/libs/app.jar` -> `/app/build/libs/app.jar`
COPY --chown=nonroot:nonroot --from=builder /app/app/build/libs/app.jar ./app.jar

EXPOSE 8080

CMD ["-jar", "app.jar"]
