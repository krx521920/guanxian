import logging

from starlette.datastructures import Headers, MutableHeaders
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.request_context import (
    REQUEST_ID_HEADER,
    get_request_id,
    reset_request_id,
    resolve_request_id,
    set_request_id,
)

logger = logging.getLogger(__name__)


class RequestBodyTooLarge(Exception):
    """流式读取的请求体超过上限。"""


def _add_response_headers(message: Message, request_id: str) -> None:
    headers = MutableHeaders(scope=message)
    headers[REQUEST_ID_HEADER] = request_id
    headers["X-Content-Type-Options"] = "nosniff"
    headers["X-Frame-Options"] = "DENY"
    headers["Referrer-Policy"] = "no-referrer"
    headers["Cache-Control"] = "no-store"
    headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"


async def _send_error(
    scope: Scope,
    send: Send,
    request_id: str,
    status: int,
    code: str,
    message: str,
) -> None:
    response = JSONResponse(
        status_code=status,
        content={"error": {"code": code, "message": message}},
    )

    async def send_with_headers(response_message: Message) -> None:
        if response_message["type"] == "http.response.start":
            _add_response_headers(response_message, request_id)
        await send(response_message)

    await response(scope, _empty_receive, send_with_headers)


async def _empty_receive() -> Message:
    return {"type": "http.request", "body": b"", "more_body": False}


class RequestBoundaryMiddleware:
    """
    在单一 ASGI 边界处管理请求 ID、安全响应头和流式请求体上限。

    不预读正文；仅在下游调用 receive 时按块统计。超限块不会交给路由，
    后续块也不会继续从客户端读取。
    """

    def __init__(self, app: ASGIApp, max_request_bytes: int) -> None:
        self.app = app
        self.max_request_bytes = max_request_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_headers = Headers(scope=scope)
        request_id = resolve_request_id(request_headers.get(REQUEST_ID_HEADER))
        context_token = set_request_id(request_id)
        response_started = False
        body_too_large = False
        bytes_received = 0

        async def limited_receive() -> Message:
            nonlocal body_too_large, bytes_received
            if body_too_large:
                raise RequestBodyTooLarge

            message = await receive()
            if message["type"] == "http.request":
                bytes_received += len(message.get("body", b""))
                if bytes_received > self.max_request_bytes:
                    body_too_large = True
                    raise RequestBodyTooLarge
            return message

        async def send_with_headers(message: Message) -> None:
            nonlocal response_started
            # FastAPI 可能将 receive 异常转换成 400；超限时丢弃该下游响应，
            # 由本中间件统一发送 413。
            if body_too_large:
                return
            if message["type"] == "http.response.start":
                _add_response_headers(message, request_id)
                response_started = True
            await send(message)

        try:
            declared_length = request_headers.get("content-length")
            if declared_length is not None:
                try:
                    declared_size = int(declared_length)
                except ValueError:
                    declared_size = -1
                if declared_size < 0:
                    await _send_error(
                        scope,
                        send,
                        request_id,
                        400,
                        "INVALID_CONTENT_LENGTH",
                        "Content-Length 请求头无效",
                    )
                    return
                if declared_size > self.max_request_bytes:
                    await _send_error(
                        scope,
                        send,
                        request_id,
                        413,
                        "PAYLOAD_TOO_LARGE",
                        "请求体超过服务限制",
                    )
                    return

            try:
                await self.app(scope, limited_receive, send_with_headers)
            except RequestBodyTooLarge:
                pass
            except Exception:
                logger.exception(
                    "Unhandled AI service error request_id=%s method=%s path=%s",
                    get_request_id(),
                    scope.get("method", ""),
                    scope.get("path", ""),
                )
                if response_started:
                    raise
                await _send_error(
                    scope,
                    send,
                    request_id,
                    500,
                    "INTERNAL_ERROR",
                    "服务暂时不可用",
                )
                return

            if body_too_large:
                if response_started:
                    # 当前 JSON 路由均会先读取请求体，此分支仅防御未来
                    # 在响应开始后才读取正文的自定义 ASGI 应用。
                    raise RuntimeError("响应开始后请求体超限，无法改写为413")
                await _send_error(
                    scope,
                    send,
                    request_id,
                    413,
                    "PAYLOAD_TOO_LARGE",
                    "请求体超过服务限制",
                )
        finally:
            reset_request_id(context_token)
