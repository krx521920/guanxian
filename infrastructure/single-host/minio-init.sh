#!/bin/sh
set -eu
# The root credential goes through the environment, not the host CLI/history.
export MC_HOST_local="http://$(cat /run/secrets/minio_root_user):$(cat /run/secrets/minio_root_password)@minio:9000"
mc mb --ignore-existing local/guanxian-private >/dev/null
mc anonymous set none local/guanxian-private >/dev/null
mc admin policy create local guanxian-app /setup/policy.json >/dev/null
# mc user-add requires process-local argv credentials. Only this isolated,
# short-lived init container receives them; never trace or print this command.
mc admin user add local "$(cat /run/secrets/storage_access_key)" "$(cat /run/secrets/storage_secret_key)" >/dev/null
mc admin policy attach local guanxian-app --user "$(cat /run/secrets/storage_access_key)" >/dev/null
echo 'Private bucket and scoped application account are ready.'
