#!/bin/sh

set -eu

app_user="app"
app_group="app"

group_name_by_gid=$(getent group "${HOST_GID}" | cut -d: -f1 || true)
gid_by_group_name=$(getent group "${app_group}" | cut -d: -f3 || true)

if [ -z "${group_name_by_gid}" ] && [ -z "${gid_by_group_name}" ]; then
    groupadd -g "${HOST_GID}" "${app_group}"
elif [ "${group_name_by_gid}" = "${app_group}" ]; then
    :
elif [ -n "${gid_by_group_name}" ] && [ "${gid_by_group_name}" != "${HOST_GID}" ]; then
    groupmod -g "${HOST_GID}" "${app_group}"
elif [ -n "${group_name_by_gid}" ] && [ "${group_name_by_gid}" != "${app_group}" ]; then
    groupmod -n "${app_group}" "${group_name_by_gid}"
fi

user_name_by_uid=$(getent passwd "${HOST_UID}" | cut -d: -f1 || true)
uid_by_user_name=$(getent passwd "${app_user}" | cut -d: -f3 || true)

if [ -z "${user_name_by_uid}" ] && [ -z "${uid_by_user_name}" ]; then
    useradd -u "${HOST_UID}" -g "${app_group}" -m -s /bin/bash "${app_user}"
elif [ "${user_name_by_uid}" = "${app_user}" ]; then
    :
elif [ -n "${uid_by_user_name}" ] && [ "${uid_by_user_name}" != "${HOST_UID}" ]; then
    usermod -u "${HOST_UID}" "${app_user}"
elif [ -n "${user_name_by_uid}" ] && [ "${user_name_by_uid}" != "${app_user}" ]; then
    usermod -l "${app_user}" -d "/home/${app_user}" -m "${user_name_by_uid}"
fi
