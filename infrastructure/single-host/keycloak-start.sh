#!/bin/bash
set -euo pipefail
# Do not enable shell tracing. Credentials are read after container startup.
export KC_DB_PASSWORD="$(</run/secrets/keycloak_db_password)"
export KC_BOOTSTRAP_ADMIN_USERNAME=identity-bootstrap
export KC_BOOTSTRAP_ADMIN_PASSWORD="$(</run/secrets/keycloak_admin_password)"
exec /opt/keycloak/bin/kc.sh start --import-realm
