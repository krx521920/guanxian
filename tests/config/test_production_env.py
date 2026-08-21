from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "deployment"))

from validate_production_env import load_env_file, validate  # noqa: E402


def valid_environment() -> dict[str, str]:
    return {
        "POSTGRES_PASSWORD": "postgres-production-password-32",
        "MINIO_ROOT_USER": "minio-production-admin",
        "MINIO_ROOT_PASSWORD": "minio-production-password-32",
        "SPRING_PROFILES_ACTIVE": "production",
        "GUANXIAN_SECURITY_MODE": "jwt",
        "GUANXIAN_MEMBER_REPOSITORY": "postgres",
        "GUANXIAN_BUSINESS_REPOSITORY": "postgres",
        "GUANXIAN_SEED_DEMO_DATA": "false",
        "GUANXIAN_JWT_ISSUER_URI": "https://login.guanxian.test/realms/guanxian",
        "GUANXIAN_JWT_JWK_SET_URI": (
            "https://login.guanxian.test/realms/guanxian/protocol/openid-connect/certs"
        ),
        "GUANXIAN_JWT_PRINCIPAL_CLAIM": "preferred_username",
        "GUANXIAN_STORAGE_BACKEND": "minio",
        "GUANXIAN_STORAGE_BUCKET": "guanxian-private",
        "GUANXIAN_STORAGE_ENDPOINT": "https://storage.guanxian.test",
        "GUANXIAN_STORAGE_ACCESS_KEY": "guanxian-storage-app",
        "GUANXIAN_STORAGE_SECRET_KEY": "storage-production-secret-32",
        "GUANXIAN_STORAGE_REDIS_URL": "rediss://redis.internal:6380",
        "GUANXIAN_STORAGE_MAX_SIZE_BYTES": "20971520",
        "GUANXIAN_STORAGE_RATE_LIMIT_ENABLED": "true",
        "GUANXIAN_STORAGE_RATE_LIMIT_PER_MINUTE": "30",
        "SPRING_DATA_REDIS_HOST": "redis.internal",
        "SPRING_DATA_REDIS_PORT": "6379",
        "GUANXIAN_AI_PROVIDER_ENABLED": "false",
        "GUANXIAN_RAG_MAX_ESTIMATED_COST": "0.50",
        "WEB_OIDC_AUTHORITY": "https://login.guanxian.test/realms/guanxian",
        "WEB_OIDC_CLIENT_ID": "guanxian-web",
        "WEB_OIDC_REDIRECT_URI": "https://platform.guanxian.test/auth/callback",
        "WEB_OIDC_POST_LOGOUT_REDIRECT_URI": "https://platform.guanxian.test/login",
        "WEB_OIDC_SCOPE": "openid profile email",
    }


class ProductionEnvironmentValidationTest(unittest.TestCase):
    def test_accepts_complete_fail_closed_production_configuration(self) -> None:
        self.assertEqual([], validate(valid_environment()))

    def test_accepts_enabled_ai_only_with_secure_complete_provider_configuration(self) -> None:
        values = valid_environment()
        values.update(
            {
                "GUANXIAN_AI_PROVIDER_ENABLED": "true",
                "GUANXIAN_AI_PROVIDER_ENDPOINT": "https://ai.guanxian.test/v1",
                "GUANXIAN_AI_PROVIDER_API_KEY": "provider-production-key-32",
                "GUANXIAN_AI_PROVIDER_MODEL": "approved-policy-model",
            }
        )
        self.assertEqual([], validate(values))

    def test_rejects_missing_values_without_echoing_secrets(self) -> None:
        values = valid_environment()
        values["POSTGRES_PASSWORD"] = ""
        errors = validate(values)
        self.assertIn("POSTGRES_PASSWORD: required", errors)
        self.assertNotIn("postgres-production-password-32", "\n".join(errors))

    def test_rejects_demo_memory_and_demo_seed(self) -> None:
        values = valid_environment()
        values["GUANXIAN_SECURITY_MODE"] = "demo"
        values["GUANXIAN_MEMBER_REPOSITORY"] = "memory"
        values["GUANXIAN_BUSINESS_REPOSITORY"] = "memory"
        values["GUANXIAN_SEED_DEMO_DATA"] = "true"
        errors = validate(values)
        self.assertIn("GUANXIAN_SECURITY_MODE: must be jwt", errors)
        self.assertIn("GUANXIAN_MEMBER_REPOSITORY: must be postgres", errors)
        self.assertIn("GUANXIAN_BUSINESS_REPOSITORY: must be postgres", errors)
        self.assertIn("GUANXIAN_SEED_DEMO_DATA: must be false", errors)

    def test_rejects_http_loopback_and_mismatched_authority(self) -> None:
        values = valid_environment()
        values["GUANXIAN_JWT_ISSUER_URI"] = "http://127.0.0.1:8080/realms/guanxian"
        errors = validate(values)
        self.assertIn("GUANXIAN_JWT_ISSUER_URI: must be an absolute HTTPS URL", errors)
        self.assertIn("GUANXIAN_JWT_ISSUER_URI: loopback or placeholder host is forbidden", errors)
        self.assertIn("WEB_OIDC_AUTHORITY: must exactly match GUANXIAN_JWT_ISSUER_URI", errors)

    def test_rejects_unsafe_storage_and_rate_limit_configuration(self) -> None:
        values = valid_environment()
        values["GUANXIAN_STORAGE_BACKEND"] = "memory"
        values["GUANXIAN_STORAGE_ENDPOINT"] = "http://localhost:9000"
        values["GUANXIAN_STORAGE_SECRET_KEY"] = "short"
        values["GUANXIAN_STORAGE_REDIS_URL"] = "redis://localhost:6379"
        values["GUANXIAN_STORAGE_MAX_SIZE_BYTES"] = str(101 * 1024 * 1024)
        values["GUANXIAN_STORAGE_RATE_LIMIT_ENABLED"] = "false"
        values["GUANXIAN_STORAGE_RATE_LIMIT_PER_MINUTE"] = "0"
        values["SPRING_DATA_REDIS_PORT"] = "70000"
        errors = validate(values)
        self.assertIn("GUANXIAN_STORAGE_BACKEND: must be minio", errors)
        self.assertIn("GUANXIAN_STORAGE_ENDPOINT: must be an absolute HTTPS URL", errors)
        self.assertIn("GUANXIAN_STORAGE_ENDPOINT: loopback or placeholder host is forbidden", errors)
        self.assertIn("GUANXIAN_STORAGE_SECRET_KEY: must contain at least 16 characters", errors)
        self.assertIn(
            "GUANXIAN_STORAGE_REDIS_URL: must be an absolute rediss:// URL in production",
            errors,
        )
        self.assertIn(
            "GUANXIAN_STORAGE_REDIS_URL: loopback or placeholder host is forbidden",
            errors,
        )
        self.assertIn(
            "GUANXIAN_STORAGE_MAX_SIZE_BYTES: must be between 1 and 104857600",
            errors,
        )
        self.assertIn("GUANXIAN_STORAGE_RATE_LIMIT_ENABLED: must be true", errors)
        self.assertIn(
            "GUANXIAN_STORAGE_RATE_LIMIT_PER_MINUTE: must be between 1 and 10000",
            errors,
        )
        self.assertIn("SPRING_DATA_REDIS_PORT: must be between 1 and 65535", errors)

    def test_rejects_enabled_ai_without_secure_provider_and_cost_controls(self) -> None:
        values = valid_environment()
        values["GUANXIAN_AI_PROVIDER_ENABLED"] = "true"
        values["GUANXIAN_AI_PROVIDER_ENDPOINT"] = "http://localhost:8000"
        values["GUANXIAN_AI_PROVIDER_API_KEY"] = "short"
        values["GUANXIAN_AI_PROVIDER_MODEL"] = ""
        values["GUANXIAN_RAG_MAX_ESTIMATED_COST"] = "NaN"
        errors = validate(values)
        self.assertIn(
            "GUANXIAN_AI_PROVIDER_ENDPOINT: must be an absolute HTTPS URL",
            errors,
        )
        self.assertIn(
            "GUANXIAN_AI_PROVIDER_ENDPOINT: loopback or placeholder host is forbidden",
            errors,
        )
        self.assertIn(
            "GUANXIAN_AI_PROVIDER_API_KEY: weak or placeholder value is forbidden",
            errors,
        )
        self.assertIn(
            "GUANXIAN_AI_PROVIDER_MODEL: required when GUANXIAN_AI_PROVIDER_ENABLED=true",
            errors,
        )
        self.assertIn(
            "GUANXIAN_RAG_MAX_ESTIMATED_COST: must be a finite positive decimal",
            errors,
        )

    def test_rejects_weak_secrets_and_missing_openid_scope(self) -> None:
        values = valid_environment()
        values["POSTGRES_PASSWORD"] = "change_me"
        values["MINIO_ROOT_PASSWORD"] = "short"
        values["WEB_OIDC_SCOPE"] = "profile email"
        errors = validate(values)
        self.assertIn("POSTGRES_PASSWORD: known placeholder or weak value is forbidden", errors)
        self.assertIn("MINIO_ROOT_PASSWORD: must contain at least 16 characters", errors)
        self.assertIn("WEB_OIDC_SCOPE: must include openid", errors)

    def test_env_file_parser_rejects_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "duplicate.env"
            fixture.write_text("A=1\nA=2\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate key"):
                load_env_file(fixture)


if __name__ == "__main__":
    unittest.main()
