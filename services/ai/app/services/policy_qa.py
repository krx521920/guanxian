from app.schemas.policy import PolicyCitation, PolicyQARequest, PolicyQAResponse
from app.services.text import keyword_similarity, sentences

MODEL_WARNING = (
    "当前版本未连接大语言模型；答案仅由本次请求提供的政策文本做规则检索生成，"
    "不代表法律意见。"
)


def answer_policy_question(request: PolicyQARequest) -> PolicyQAResponse:
    ranked: list[tuple[float, str, object]] = []
    for document in request.documents:
        for sentence in sentences(document.content):
            score = keyword_similarity(request.question, sentence)
            if score > 0:
                ranked.append((score, sentence, document))

    ranked.sort(key=lambda item: (-item[0], item[2].document_id, item[1]))
    citations: list[PolicyCitation] = []
    seen_documents: set[str] = set()
    for score, sentence, document in ranked:
        if document.document_id in seen_documents:
            continue
        citations.append(
            PolicyCitation(
                document_id=document.document_id,
                title=document.title,
                excerpt=sentence[:300],
                relevance_score=round(min(score, 1), 3),
                source_url=document.source_url,
            )
        )
        seen_documents.add(document.document_id)
        if len(citations) >= request.top_k:
            break

    if citations:
        excerpts = "；".join(citation.excerpt for citation in citations)
        answer = f"从已提供材料中检索到以下相关内容：{excerpts}。请结合原文和主管部门解释核实。"
    else:
        answer = "已提供材料中没有检索到足以回答该问题的内容。请补充相关政策或标准原文。"

    return PolicyQAResponse(
        answer=answer,
        citations=citations,
        warning=MODEL_WARNING,
    )
