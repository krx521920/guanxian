import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from app.schemas.company import CompanyExtractionRequest
from app.services.company_extraction import extract_company_profile

SAFE_TEXT = st.text(
    alphabet=st.characters(blacklist_categories=("Cs",)),
    min_size=5,
    max_size=500,
).filter(lambda value: len(value.strip()) >= 5)


def test_extract_respects_explicit_company_name_and_detects_all_groups(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={
            "company_name": "人工确认名称",
            "text": (
                "原始名称有限公司从事燃气管线阀门生产与运维，"
                "持有ISO9001，可服务全国市场。"
            ),
        },
    )

    assert response.status_code == 200
    profile = response.json()["profile"]
    assert profile["company_name"] == "人工确认名称"
    assert profile["business_roles"] == ["运营维护", "设备制造"]
    assert profile["scenarios"] == ["燃气"]
    assert profile["qualifications"] == ["ISO9001"]
    assert profile["service_regions"] == ["全国"]
    evidence = response.json()["evidence"]
    assert {item["field"] for item in evidence} == {
        "business_roles",
        "scenarios",
        "products_services",
        "qualifications",
        "service_regions",
    }
    assert {item["keyword"] for item in evidence if item["field"] == "business_roles"} == {
        "运维",
        "生产",
    }


def test_extract_unrelated_text_does_not_invent_industry_attributes(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={"text": "这是一段仅用于边界校验的普通介绍文字。"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["profile"]["business_roles"] == []
    assert body["profile"]["scenarios"] == []
    assert body["profile"]["products_services"] == []
    assert body["confidence"] == 0.35
    assert len(body["warnings"]) == 2


def test_extract_rejects_trimmed_too_short_oversized_and_wrong_type(client):
    payloads = [
        {"text": "  abc  "},
        {"text": "管" * 50_001},
        {"text": ["管线企业"]},
        {"text": None},
    ]

    for payload in payloads:
        response = client.post("/api/v1/extract/company-profile", json=payload)
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_extract_accepts_exact_minimum_after_trimming(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={"text": "  abcde  "},
    )

    assert response.status_code == 200
    assert response.json()["profile"]["summary"] == "abcde"


def test_extract_normalizes_full_width_keywords_when_name_hint_is_omitted(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={
            "text": "北京数字管线有限公司提供ＧＩＳ平台与管线检测服务。",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["profile"]["company_name"] == "北京数字管线有限公司"
    assert "GIS" in body["profile"]["products_services"]
    assert any(item["keyword"] == "GIS" for item in body["evidence"])


def test_extract_rejects_blank_company_name_hint(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={"company_name": "   ", "text": "这是一段合法的企业介绍。"},
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_extract_rejects_malformed_json(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        content=b'{"text":',
        headers={"content-type": "application/json"},
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


@pytest.mark.fuzz
@given(text=SAFE_TEXT)
@settings(max_examples=50, deadline=None, derandomize=True)
def test_extraction_fuzz_is_deterministic_bounded_and_deduplicated(text):
    request = CompanyExtractionRequest(text=text)

    first = extract_company_profile(request)
    second = extract_company_profile(request)

    assert first == second
    assert 0 <= first.confidence <= 1
    assert len(first.profile.summary) <= 300
    assert len(first.evidence) <= 100
    for values in (
        first.profile.business_roles,
        first.profile.scenarios,
        first.profile.products_services,
        first.profile.qualifications,
        first.profile.service_regions,
        first.profile.tags,
    ):
        assert len(values) == len(set(values))
