import asyncio
import json
from collections.abc import Sequence

from starlette.types import Message, Receive, Scope, Send

from app.middleware import RequestBodyTooLarge, RequestBoundaryMiddleware
from app.request_context import get_request_id


def _run_chunked_request(
    chunks: Sequence[bytes],
    *,
    limit: int,
    declared_length: int | None = None,
    translate_receive_error: bool = False,
) -> tuple[list[Message], list[bytes], int]:
    sent: list[Message] = []
    delivered_to_route: list[bytes] = []
    receive_calls = 0
    incoming = [
        {
            "type": "http.request",
            "body": chunk,
            "more_body": index < len(chunks) - 1,
        }
        for index, chunk in enumerate(chunks)
    ]

    headers = [(b"x-request-id", b"chunked-test-1")]
    if declared_length is not None:
        headers.append((b"content-length", str(declared_length).encode("ascii")))
    scope: Scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/chunked-test",
        "raw_path": b"/chunked-test",
        "query_string": b"",
        "root_path": "",
        "headers": headers,
        "client": ("test-client", 1234),
        "server": ("test-server", 80),
    }

    async def receive() -> Message:
        nonlocal receive_calls
        message = incoming[receive_calls]
        receive_calls += 1
        return message

    async def send(message: Message) -> None:
        sent.append(message)

    async def consume_body(_scope: Scope, app_receive: Receive, app_send: Send) -> None:
        try:
            more_body = True
            while more_body:
                message = await app_receive()
                delivered_to_route.append(message.get("body", b""))
                more_body = message.get("more_body", False)
        except RequestBodyTooLarge:
            if not translate_receive_error:
                raise
            # 模拟 FastAPI 将请求体读取异常转成 400 的行为。
            await app_send(
                {"type": "http.response.start", "status": 400, "headers": []}
            )
            await app_send(
                {
                    "type": "http.response.body",
                    "body": b'{"detail":"parse error"}',
                    "more_body": False,
                }
            )
            return
        await app_send(
            {
                "type": "http.response.start",
                "status": 200,
                "headers": [(b"content-type", b"application/json")],
            }
        )
        await app_send(
            {
                "type": "http.response.body",
                "body": b'{"status":"accepted"}',
                "more_body": False,
            }
        )

    middleware = RequestBoundaryMiddleware(consume_body, max_request_bytes=limit)
    asyncio.run(middleware(scope, receive, send))
    return sent, delivered_to_route, receive_calls


def _response_status(messages: list[Message]) -> int:
    start = next(message for message in messages if message["type"] == "http.response.start")
    return start["status"]


def _response_headers(messages: list[Message]) -> dict[str, str]:
    start = next(message for message in messages if message["type"] == "http.response.start")
    return {
        key.decode("latin-1").lower(): value.decode("latin-1")
        for key, value in start["headers"]
    }


def _response_json(messages: list[Message]) -> dict:
    body = b"".join(
        message.get("body", b"")
        for message in messages
        if message["type"] == "http.response.body"
    )
    return json.loads(body)


def test_chunked_body_exactly_at_limit_reaches_route_without_prebuffering():
    messages, delivered, receive_calls = _run_chunked_request(
        [b"ab", b"cde"],
        limit=5,
    )

    assert _response_status(messages) == 200
    assert delivered == [b"ab", b"cde"]
    assert receive_calls == 2
    assert _response_headers(messages)["x-request-id"] == "chunked-test-1"
    assert get_request_id() is None


def test_chunked_body_one_byte_over_limit_returns_413_and_stops_delivery():
    messages, delivered, receive_calls = _run_chunked_request(
        [b"ab", b"cdef", b"must-not-be-read"],
        limit=5,
        translate_receive_error=True,
    )

    assert _response_status(messages) == 413
    assert _response_json(messages) == {
        "error": {"code": "PAYLOAD_TOO_LARGE", "message": "请求体超过服务限制"}
    }
    assert delivered == [b"ab"]
    assert receive_calls == 2
    headers = _response_headers(messages)
    assert headers["x-request-id"] == "chunked-test-1"
    assert headers["x-content-type-options"] == "nosniff"
    assert [message["type"] for message in messages] == [
        "http.response.start",
        "http.response.body",
    ]
    assert get_request_id() is None


def test_declared_oversized_body_is_rejected_without_calling_receive_or_route():
    messages, delivered, receive_calls = _run_chunked_request(
        [b"body-must-not-be-read"],
        limit=5,
        declared_length=6,
    )

    assert _response_status(messages) == 413
    assert _response_json(messages)["error"]["code"] == "PAYLOAD_TOO_LARGE"
    assert delivered == []
    assert receive_calls == 0
    assert len(messages) == 2
    assert get_request_id() is None
