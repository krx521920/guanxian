#!/usr/bin/env bash
set -euo pipefail

contract_root="${RUNNER_TEMP}/guanxian-production-contract"
secret_root="${contract_root}/secrets"
runtime_root="${contract_root}/runtime"
mkdir -p "${secret_root}" "${runtime_root}"

write_secret() {
  local name="$1"
  local value="$2"
  printf '%s' "${value}" > "${secret_root}/${name}"
  chmod 600 "${secret_root}/${name}"
}

postgres_password="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
storage_secret="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
redis_password="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
write_secret postgres_password "${postgres_password}"
write_secret storage_access_key 'ci-storage-app'
write_secret storage_secret_key "${storage_secret}"
write_secret storage_redis_url 'rediss://redis.ci.internal:6380'
write_secret embedding_api_key ''
write_secret ai_provider_api_key ''
write_secret tls_certificate 'ci-compose-contract-certificate'
write_secret tls_private_key 'ci-compose-contract-private-key'
write_secret prometheus_scrape_token "$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
write_secret minio_prometheus_bearer_token "$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
write_secret grafana_admin_password "$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
write_secret redis_exporter_passwords "{\"rediss://redis.ci.internal:6380\":\"${redis_password}\"}"
write_secret alert_webhook_url 'https://alerts.operator.test/hooks/guanxian-ci'

python tools/operations/render_alertmanager_config.py \
  --webhook-url-file "${secret_root}/alert_webhook_url" \
  --output "${runtime_root}/alertmanager.yml"
python tools/operations/render_minio_prometheus_target.py \
  --endpoint 'https://storage.ci.internal' \
  --output "${runtime_root}/minio-target.json"

cat >> "${GITHUB_ENV}" <<EOF
POSTGRES_PASSWORD_FILE=${secret_root}/postgres_password
STORAGE_ACCESS_KEY_FILE=${secret_root}/storage_access_key
STORAGE_SECRET_KEY_FILE=${secret_root}/storage_secret_key
STORAGE_REDIS_URL_FILE=${secret_root}/storage_redis_url
EMBEDDING_API_KEY_FILE=${secret_root}/embedding_api_key
AI_PROVIDER_API_KEY_FILE=${secret_root}/ai_provider_api_key
TLS_CERTIFICATE_FILE=${secret_root}/tls_certificate
TLS_PRIVATE_KEY_FILE=${secret_root}/tls_private_key
PROMETHEUS_SCRAPE_TOKEN_FILE=${secret_root}/prometheus_scrape_token
MINIO_PROMETHEUS_BEARER_TOKEN_FILE=${secret_root}/minio_prometheus_bearer_token
MINIO_PROMETHEUS_TARGET_FILE=${runtime_root}/minio-target.json
REDIS_EXPORTER_PASSWORD_FILE=${secret_root}/redis_exporter_passwords
GRAFANA_ADMIN_PASSWORD_FILE=${secret_root}/grafana_admin_password
ALERTMANAGER_CONFIG_FILE=${runtime_root}/alertmanager.yml
EOF
