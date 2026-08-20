from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """服务配置；均可通过 ``AI_`` 前缀环境变量覆盖。"""

    model_config = SettingsConfigDict(
        env_prefix="AI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "北京地下管线协会 AI 服务"
    version: str = "0.1.0"
    environment: str = "development"
    debug: bool = False
    api_prefix: str = "/api/v1"
    host: str = "0.0.0.0"
    port: int = 8001
    max_request_bytes: int = Field(default=32 * 1024 * 1024, ge=1)
    model_provider: str = "disabled"
    model_name: str | None = None

    @property
    def model_connected(self) -> bool:
        return self.model_provider.lower() not in {"", "disabled", "none"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
