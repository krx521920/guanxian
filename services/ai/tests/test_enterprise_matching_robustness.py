import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from app.schemas.matching import EnterpriseMatchRequest
from app.services.enterprise_matching import match_enterprises

SHORT_TEXT = st.text(
    alphabet=st.characters(blacklist_categories=("Cs",)),
    min_size=1,
    max_size=30,
).filter(lambda value: bool(value.strip()))


def test_match_applies_top_k_and_stable_id_tie_break(client):
    payload = {
            "demand": {"demand_id": "D-TIE", "title": "无关需求"},
            "candidates": [
                {"enterprise_id": "E-2", "enterprise_name": "乙企业"},
                {"enterprise_id": "E-1", "enterprise_name": "甲企业"},
                {"enterprise_id": "E-3", "enterprise_name": "丙企业"},
            ],
            "top_k": 2,
        }
    response = client.post("/api/v1/match/enterprises", json=payload)
    reversed_response = client.post(
        "/api/v1/match/enterprises",
        json={**payload, "candidates": list(reversed(payload["candidates"]))},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["total_candidates"] == 3
    assert [item["enterprise_id"] for item in body["matches"]] == ["E-1", "E-2"]
    assert reversed_response.json()["matches"] == body["matches"]


def test_match_equal_relative_weights_full_match_is_exactly_100(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {
                "demand_id": "D-FULL",
                "title": "燃气阀门",
                "scenarios": ["燃气"],
                "required_capabilities": ["阀门"],
                "required_qualifications": ["ISO9001"],
                "region": "北京",
            },
            "candidates": [
                {
                    "enterprise_id": "E-FULL",
                    "enterprise_name": "完全匹配企业",
                    "scenarios": ["燃气"],
                    "capabilities": ["阀门"],
                    "qualifications": ["ISO9001"],
                    "service_regions": ["北京"],
                    "case_count": 3,
                    "data_completeness": 1,
                    "updated_days_ago": 0,
                }
            ],
            "weights": {
                "scenario": 1,
                "capability": 1,
                "qualification": 1,
                "case": 1,
                "region": 1,
                "data_quality": 1,
            },
        },
    )

    assert response.status_code == 200
    match = response.json()["matches"][0]
    assert match["score"] == 100
    assert sum(match["breakdown"].values()) == 100


def test_match_recognizes_normalized_nationwide_region(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-R", "title": "服务", "region": "北京"},
            "candidates": [
                {
                    "enterprise_id": "E-R",
                    "enterprise_name": "全国服务企业",
                    "service_regions": ["全 国"],
                }
            ],
        },
    )

    assert response.status_code == 200
    match = response.json()["matches"][0]
    assert match["breakdown"]["region"] == 10
    assert "可服务 北京" in match["reasons"]


def test_match_rejects_empty_candidates_invalid_bounds_and_negative_metrics(client):
    base = {"demand": {"demand_id": "D-1", "title": "测试需求"}}
    invalid_payloads = [
        {**base, "candidates": []},
        {
            **base,
            "candidates": [{"enterprise_id": "E", "enterprise_name": "企业"}],
            "top_k": 0,
        },
        {
            **base,
            "candidates": [
                {"enterprise_id": "E", "enterprise_name": "企业", "case_count": -1}
            ],
        },
        {
            **base,
            "candidates": [
                {
                    "enterprise_id": "E",
                    "enterprise_name": "企业",
                    "data_completeness": 1.01,
                }
            ],
        },
    ]

    for payload in invalid_payloads:
        response = client.post("/api/v1/match/enterprises", json=payload)
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_match_rejects_boolean_values_for_numeric_fields(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-BOOL", "title": "数值类型边界"},
            "candidates": [
                {
                    "enterprise_id": "E-BOOL",
                    "enterprise_name": "布尔值异常企业",
                    "case_count": False,
                    "data_completeness": True,
                    "updated_days_ago": False,
                }
            ],
            "top_k": True,
        },
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_match_accepts_maximum_candidate_and_result_bounds(client):
    candidates = [
        {"enterprise_id": f"E-{index:03}", "enterprise_name": f"企业{index:03}"}
        for index in range(500)
    ]
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-MAX", "title": "边界测试"},
            "candidates": candidates,
            "top_k": 100,
        },
    )

    assert response.status_code == 200
    assert response.json()["total_candidates"] == 500
    assert len(response.json()["matches"]) == 100


def test_missing_required_qualification_always_makes_candidate_ineligible(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {
                "demand_id": "D-Q",
                "title": "燃气阀门",
                "scenarios": ["燃气"],
                "required_capabilities": ["阀门"],
                "required_qualifications": ["安全生产许可证"],
                "region": "北京",
            },
            "candidates": [
                {
                    "enterprise_id": "E-NO-CERT",
                    "enterprise_name": "高分但无资质企业",
                    "scenarios": ["燃气"],
                    "capabilities": ["阀门"],
                    "service_regions": ["全国"],
                    "case_count": 99,
                    "data_completeness": 1,
                    "updated_days_ago": 0,
                }
            ],
        },
    )

    match = response.json()["matches"][0]
    assert match["eligible"] is False
    assert "缺少要求资质：安全生产许可证" in match["missing_conditions"]


@pytest.mark.fuzz
@given(
    title=SHORT_TEXT,
    scenario=SHORT_TEXT,
    capability=SHORT_TEXT,
    qualification=SHORT_TEXT,
    region=SHORT_TEXT,
    case_count=st.integers(min_value=0, max_value=10_000),
    completeness=st.floats(min_value=0, max_value=1, allow_nan=False),
    age=st.integers(min_value=0, max_value=36_500),
)
@settings(max_examples=60, deadline=None, derandomize=True)
def test_matching_fuzz_preserves_score_and_eligibility_invariants(
    title,
    scenario,
    capability,
    qualification,
    region,
    case_count,
    completeness,
    age,
):
    request = EnterpriseMatchRequest.model_validate(
        {
            "demand": {
                "demand_id": "D-FUZZ",
                "title": title,
                "scenarios": [scenario],
                "required_capabilities": [capability],
                "required_qualifications": [qualification],
                "region": region,
            },
            "candidates": [
                {
                    "enterprise_id": "E-FUZZ",
                    "enterprise_name": "属性测试企业",
                    "description": title,
                    "scenarios": [scenario],
                    "capabilities": [capability],
                    "qualifications": [qualification],
                    "service_regions": [region],
                    "case_count": case_count,
                    "data_completeness": completeness,
                    "updated_days_ago": age,
                }
            ],
        }
    )

    result = match_enterprises(request)
    match = result.matches[0]

    assert match.eligible is True
    assert 0 <= match.score <= 100
    assert match.score == round(sum(match.breakdown.model_dump().values()), 2)
    assert all(0 <= score <= 35 for score in match.breakdown.model_dump().values())
    assert result.total_candidates == 1
