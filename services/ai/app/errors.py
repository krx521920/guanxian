from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

MAX_VALIDATION_ERRORS = 50


class DomainError(Exception):
    def __init__(self, code: str, message: str, *, status_code: int = 400) -> None:
        self.code = code
        self.message = message
        self.status_code = status_code
        super().__init__(message)


def _error_body(code: str, message: str, details: Any = None) -> dict[str, Any]:
    body: dict[str, Any] = {"error": {"code": code, "message": message}}
    if details is not None:
        body["error"]["details"] = details
    return body


def _safe_validation_details(exc: RequestValidationError) -> list[dict[str, Any]]:
    """仅返回定位信息，避免 Pydantic 的 input/ctx 回显用户原文或密钥。"""

    return [
        {
            "location": list(error.get("loc", ())),
            "type": str(error.get("type", "validation_error")),
            "message": str(error.get("msg", "请求参数无效")),
        }
        for error in exc.errors()[:MAX_VALIDATION_ERRORS]
    ]


def register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(DomainError)
    async def handle_domain_error(_: Request, exc: DomainError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=_error_body(exc.code, exc.message),
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        _: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content=_error_body(
                "VALIDATION_ERROR",
                "请求参数校验失败",
                _safe_validation_details(exc),
            ),
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_error(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        # Malformed bytes are rejected by Starlette before Pydantic validates
        # them. Normalize that case to FastAPI's documented 422 contract.
        if exc.status_code == 400:
            return JSONResponse(
                status_code=422,
                content=_error_body("VALIDATION_ERROR", "请求体不是有效的JSON"),
            )
        return JSONResponse(
            status_code=exc.status_code,
            content=_error_body("HTTP_ERROR", str(exc.detail)),
            headers=exc.headers,
        )
