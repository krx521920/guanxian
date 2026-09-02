def test_policy_qa_is_explicitly_unavailable(client):
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

    assert response.status_code == 503
    body = response.json()
    assert body == {
        "error": {
            "code": "POLICY_QA_NOT_ENABLED",
            "message": "Python 服务未启用政策问答，请使用 Java ai-adapter 的知识库问答接口",
        }
    }


def test_policy_qa_without_documents_is_still_unavailable(client):
    response = client.post(
        "/api/v1/qa/policy",
        json={"question": "最新政策是什么？"},
    )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "POLICY_QA_NOT_ENABLED"
