from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app


def test_validation_errors_do_not_echo_input_or_internal_context(client):
    secret = "SECRET-API-KEY-DO-NOT-ECHO"
    response = client.post(
        "/api/v1/extract/company-profile",
        json={"text": secret * 3_000},
    )

    assert response.status_code == 422
    serialized = response.text
    assert secret not in serialized
    details = response.json()["error"]["details"]
    assert details
    assert set(details[0]) == {"location", "type", "message"}


def test_validation_error_details_are_capped(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-ERRORS", "title": "错误数量上限"},
            "candidates": [{} for _ in range(40)],
        },
    )

    assert response.status_code == 422
    assert len(response.json()["error"]["details"]) == 50


def test_unexpected_errors_return_generic_response_without_exception_text(monkeypatch):
    secret = "DATABASE-PASSWORD-SHOULD-STAY-INTERNAL"

    def fail_safely(_request):
        raise RuntimeError(secret)

    monkeypatch.setattr("app.api.routes.extract_company_profile", fail_safely)
    with TestClient(app, raise_server_exceptions=False) as isolated_client:
        response = isolated_client.post(
            "/api/v1/extract/company-profile",
            json={"text": "这是一段有效的企业介绍。"},
        )

    assert response.status_code == 500
    assert response.json() == {
        "error": {"code": "INTERNAL_ERROR", "message": "服务暂时不可用"}
    }
    assert secret not in response.text


def test_declared_oversized_request_is_rejected_before_body_processing(client):
    request_id = "oversized-request-1"
    response = client.post(
        "/api/v1/extract/company-profile",
        json={"text": "这是一段合法的企业介绍。"},
        headers={
            "content-length": str(get_settings().max_request_bytes + 1),
            "X-Request-Id": request_id,
        },
    )

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["permissions-policy"] == (
        "camera=(), microphone=(), geolocation=()"
    )
    assert response.headers["X-Request-Id"] == request_id
