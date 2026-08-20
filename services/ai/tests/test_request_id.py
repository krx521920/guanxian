from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Lock
from uuid import UUID

from app.errors import DomainError
from app.request_context import get_request_id, resolve_request_id
from app.services.company_extraction import extract_company_profile


def test_valid_request_id_is_preserved_on_success_and_validation_error(client):
    request_id = "assoc.api:member-01_trace.2"

    success = client.get("/health", headers={"X-Request-Id": request_id})
    validation_error = client.post(
        "/api/v1/extract/company-profile",
        json={"text": " "},
        headers={"X-Request-Id": request_id},
    )

    assert success.status_code == 200
    assert validation_error.status_code == 422
    assert success.headers["X-Request-Id"] == request_id
    assert validation_error.headers["X-Request-Id"] == request_id

    maximum_id = "x" * 128
    maximum_response = client.get("/health", headers={"X-Request-Id": maximum_id})
    assert maximum_response.headers["X-Request-Id"] == maximum_id
    assert get_request_id() is None


def test_missing_or_invalid_request_id_is_replaced_with_uuid(client):
    invalid_values = [
        None,
        "",
        "contains space",
        "contains/slash",
        "x" * 129,
    ]

    generated: list[str] = []
    for value in invalid_values:
        headers = {} if value is None else {"X-Request-Id": value}
        response = client.get("/health", headers=headers)

        assert response.status_code == 200
        generated_id = response.headers["X-Request-Id"]
        assert str(UUID(generated_id)) == generated_id
        generated.append(generated_id)

    assert len(generated) == len(set(generated))
    for value in ("contains\nnewline", "中文请求号"):
        generated_id = resolve_request_id(value)
        assert str(UUID(generated_id)) == generated_id
    assert get_request_id() is None


def test_request_id_is_added_to_business_and_unexpected_error_responses(
    client, monkeypatch, caplog
):
    business_id = "business-error-1"

    def raise_business_error(_request):
        raise DomainError("BUSINESS_RULE", "业务规则不满足", status_code=409)

    monkeypatch.setattr("app.api.routes.extract_company_profile", raise_business_error)
    business_response = client.post(
        "/api/v1/extract/company-profile",
        json={"text": "这是一段有效的企业介绍。"},
        headers={"X-Request-Id": business_id},
    )

    assert business_response.status_code == 409
    assert business_response.headers["X-Request-Id"] == business_id

    exception_id = "unexpected-error-1"

    def raise_unexpected_error(_request):
        raise RuntimeError("不应向客户端泄露的异常")

    monkeypatch.setattr("app.api.routes.extract_company_profile", raise_unexpected_error)
    with caplog.at_level("ERROR"):
        exception_response = client.post(
            "/api/v1/extract/company-profile",
            json={"text": "这是另一段有效的企业介绍。"},
            headers={"X-Request-Id": exception_id},
        )

    assert exception_response.status_code == 500
    assert exception_response.headers["X-Request-Id"] == exception_id
    assert exception_id in caplog.text
    assert sum(exception_id in record.getMessage() for record in caplog.records) == 1
    assert get_request_id() is None


def test_concurrent_requests_keep_request_context_isolated(client, monkeypatch):
    request_count = 8
    barrier = Barrier(request_count)
    seen: dict[str, str | None] = {}
    seen_lock = Lock()

    def observe_request_id(request):
        barrier.wait(timeout=10)
        with seen_lock:
            seen[request.company_name] = get_request_id()
        return extract_company_profile(request)

    monkeypatch.setattr("app.api.routes.extract_company_profile", observe_request_id)

    def send(index: int) -> tuple[str, str]:
        request_id = f"parallel-{index}"
        response = client.post(
            "/api/v1/extract/company-profile",
            json={
                "company_name": f"company-{index}",
                "text": "这是用于并发隔离测试的企业介绍。",
            },
            headers={"X-Request-Id": request_id},
        )
        assert response.status_code == 200
        return request_id, response.headers["X-Request-Id"]

    with ThreadPoolExecutor(max_workers=request_count) as pool:
        results = list(pool.map(send, range(request_count)))

    assert all(sent == returned for sent, returned in results)
    assert seen == {f"company-{index}": f"parallel-{index}" for index in range(request_count)}
    assert get_request_id() is None
