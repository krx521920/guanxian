def test_extract_company_profile(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={
            "text": (
                "北京示例科技有限公司是一家高新技术企业，面向北京燃气和热力场景，"
                "提供阀门、监测系统和数字孪生管理平台的生产、安装与运维服务。"
            )
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["profile"]["company_name"] == "北京示例科技有限公司"
    assert "燃气" in body["profile"]["scenarios"]
    assert "阀门" in body["profile"]["products_services"]
    assert body["processing_mode"] == "deterministic_rules"
    assert body["confidence"] > 0.5


def test_extract_rejects_blank_text(client):
    response = client.post(
        "/api/v1/extract/company-profile",
        json={"text": "     "},
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
