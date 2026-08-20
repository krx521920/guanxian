def test_matching_rejects_duplicate_enterprise_ids_and_zero_weights(client):
    duplicate_ids = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-1", "title": "测试需求"},
            "candidates": [
                {"enterprise_id": "E-1", "enterprise_name": "甲企业"},
                {"enterprise_id": "ｅ－１", "enterprise_name": "乙企业"},
            ],
        },
    )
    zero_weights = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-1", "title": "测试需求"},
            "candidates": [{"enterprise_id": "E-1", "enterprise_name": "甲企业"}],
            "weights": {
                "scenario": 0,
                "capability": 0,
                "qualification": 0,
                "case": 0,
                "region": 0,
                "data_quality": 0,
            },
        },
    )

    assert duplicate_ids.status_code == 409
    assert duplicate_ids.json()["error"]["code"] == "DUPLICATE_ENTERPRISE_ID"
    assert zero_weights.status_code == 409
    assert zero_weights.json()["error"]["code"] == "INVALID_MATCHING_WEIGHTS"


def test_matching_deduplicates_labels_and_rejects_resource_abuse(client):
    accepted = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {
                "demand_id": " D-1 ",
                "title": " 测试需求 ",
                "scenarios": ["燃气", " 燃气 ", "ＧＩＳ", "gis"],
            },
            "candidates": [{"enterprise_id": "E-1", "enterprise_name": "甲企业"}],
        },
    )
    too_many_labels = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {
                "demand_id": "D-1",
                "title": "测试需求",
                "scenarios": [f"场景{index}" for index in range(51)],
            },
            "candidates": [{"enterprise_id": "E-1", "enterprise_name": "甲企业"}],
        },
    )
    huge_metrics = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-1", "title": "测试需求"},
            "candidates": [
                {
                    "enterprise_id": "E-1",
                    "enterprise_name": "甲企业",
                    "case_count": 10**100,
                    "updated_days_ago": 10**100,
                }
            ],
        },
    )

    assert accepted.status_code == 200
    assert accepted.json()["demand_id"] == "D-1"
    assert too_many_labels.status_code == 422
    assert huge_metrics.status_code == 422


def test_policy_rejects_duplicate_ids_as_a_business_conflict(client):
    response = client.post(
        "/api/v1/qa/policy",
        json={
            "question": "政策问题",
            "documents": [
                {"document_id": "P-1", "title": "政策甲", "content": "内容甲"},
                {"document_id": "ｐ－１", "title": "政策乙", "content": "内容乙"},
            ],
        },
    )

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "DUPLICATE_DOCUMENT_ID"


def test_policy_rejects_blank_content_and_unsafe_urls(client):
    payloads = [
        {
            "question": "政策问题",
            "documents": [{"document_id": "P-1", "title": "政策", "content": "   "}],
        },
        {
            "question": "政策问题",
            "documents": [
                {
                    "document_id": "P-1",
                    "title": "政策",
                    "content": "政策内容",
                    "source_url": "javascript:alert(1)",
                }
            ],
        },
        {
            "question": "政策问题",
            "documents": [
                {
                    "document_id": "P-1",
                    "title": "政策",
                    "content": "政策内容",
                    "source_url": "https://user:secret@example.com/policy",
                }
            ],
        },
    ]

    for payload in payloads:
        response = client.post("/api/v1/qa/policy", json=payload)
        assert response.status_code == 422


def test_policy_accepts_safe_https_source_and_trims_identifiers(client):
    response = client.post(
        "/api/v1/qa/policy",
        json={
            "question": "施工安全要求",
            "documents": [
                {
                    "document_id": " P-1 ",
                    "title": " 安全规定 ",
                    "content": "施工安全要求开展现场交底。",
                    "source_url": " https://example.com/policy?id=1 ",
                }
            ],
        },
    )

    assert response.status_code == 200
    citation = response.json()["citations"][0]
    assert citation["document_id"] == "P-1"
    assert citation["title"] == "安全规定"
    assert citation["source_url"] == "https://example.com/policy?id=1"
