from concurrent.futures import ThreadPoolExecutor

import pytest

from app.schemas.company import CompanyExtractionRequest
from app.schemas.matching import EnterpriseMatchRequest
from app.schemas.policy import PolicyQARequest
from app.services.company_extraction import extract_company_profile
from app.services.enterprise_matching import match_enterprises
from app.services.policy_qa import answer_policy_question


def _exercise_all_services(index: int) -> tuple[int, int, int]:
    extraction = extract_company_profile(
        CompanyExtractionRequest(
            text=f"北京压力测试{index}有限公司提供燃气阀门、检测和运维服务。"
        )
    )
    matching = match_enterprises(
        EnterpriseMatchRequest.model_validate(
            {
                "demand": {
                    "demand_id": f"D-{index}",
                    "title": "燃气阀门",
                    "scenarios": ["燃气"],
                    "required_capabilities": ["阀门"],
                },
                "candidates": [
                    {
                        "enterprise_id": f"E-{index}",
                        "enterprise_name": "压力测试企业",
                        "scenarios": ["燃气"],
                        "capabilities": ["阀门"],
                    }
                ],
            }
        )
    )
    qa = answer_policy_question(
        PolicyQARequest.model_validate(
            {
                "question": "施工前如何查明地下管线？",
                "documents": [
                    {
                        "document_id": f"P-{index}",
                        "title": "管理办法",
                        "content": "施工前应查明既有地下管线情况。",
                    }
                ],
            }
        )
    )
    return len(extraction.profile.tags), len(matching.matches), len(qa.citations)


@pytest.mark.stress
def test_services_survive_concurrent_high_load():
    iterations = 2_000
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(_exercise_all_services, range(iterations)))

    assert len(results) == iterations
    assert all(
        tag_count >= 3 and match_count == 1 and citation_count == 1
        for tag_count, match_count, citation_count in results
    )
