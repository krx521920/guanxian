def test_policy_qa_returns_retrieved_citation_and_warning(client):
    response = client.post(
        "/api/v1/qa/policy",
        json={
            "question": "地下管线施工前需要做什么？",
            "documents": [
                {
                    "document_id": "P-001",
                    "title": "地下管线施工管理办法",
                    "content": "地下管线施工前应查明既有管线情况。建设单位应组织安全交底。",
                }
            ],
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["model_connected"] is False
    assert body["processing_mode"] == "placeholder_retrieval"
    assert "未连接大语言模型" in body["warning"]
    assert body["citations"][0]["document_id"] == "P-001"


def test_policy_qa_without_documents_is_explicit(client):
    response = client.post(
        "/api/v1/qa/policy",
        json={"question": "最新政策是什么？"},
    )

    assert response.status_code == 200
    assert response.json()["citations"] == []
    assert "没有检索到" in response.json()["answer"]
