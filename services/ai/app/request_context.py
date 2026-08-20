import re
from contextvars import ContextVar, Token
from uuid import uuid4

REQUEST_ID_HEADER = "X-Request-Id"
REQUEST_ID_PATTERN = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")
_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)


def resolve_request_id(value: str | None) -> str:
    """接受与 Java 服务一致的请求 ID，其他情况生成新 UUID。"""

    if value is not None and REQUEST_ID_PATTERN.fullmatch(value):
        return value
    return str(uuid4())


def set_request_id(value: str) -> Token[str | None]:
    return _request_id.set(value)


def get_request_id() -> str | None:
    return _request_id.get()


def reset_request_id(token: Token[str | None]) -> None:
    _request_id.reset(token)
