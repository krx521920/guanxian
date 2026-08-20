def test_match_enterprises_is_explainable_and_sorted(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {
                "demand_id": "D-001",
                "title": "燃气管线阀门供应",
                "scenarios": ["燃气"],
                "required_capabilities": ["阀门"],
                "required_qualifications": ["ISO9001"],
                "region": "北京",
            },
            "candidates": [
                {
                    "enterprise_id": "E-LOW",
                    "enterprise_name": "普通施工企业",
                    "scenarios": ["排水"],
                    "capabilities": ["施工"],
                    "service_regions": ["天津"],
                },
                {
                    "enterprise_id": "E-HIGH",
                    "enterprise_name": "燃气阀门企业",
                    "scenarios": ["燃气"],
                    "capabilities": ["阀门"],
                    "qualifications": ["ISO9001"],
                    "service_regions": ["北京"],
                    "case_count": 4,
                    "data_completeness": 1,
                    "updated_days_ago": 3,
                },
            ],
        },
    )

    assert response.status_code == 200
    matches = response.json()["matches"]
    assert matches[0]["enterprise_id"] == "E-HIGH"
    assert matches[0]["eligible"] is True
    assert matches[0]["score"] > matches[1]["score"]
    assert matches[0]["breakdown"]["scenario"] == 35
    assert "满足全部必需资质" in matches[0]["reasons"]
    assert matches[1]["eligible"] is False


def test_match_weights_are_normalized_to_100(client):
    response = client.post(
        "/api/v1/match/enterprises",
        json={
            "demand": {"demand_id": "D-1", "title": "测试需求"},
            "candidates": [
                {"enterprise_id": "E-1", "enterprise_name": "测试企业"}
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
    breakdown = response.json()["matches"][0]["breakdown"]
    assert round(sum(breakdown.values()), 2) <= 100
