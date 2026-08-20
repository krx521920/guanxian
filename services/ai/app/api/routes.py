from typing import Annotated

from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.errors import DomainError
from app.schemas.common import canonical_key
from app.schemas.company import CompanyExtractionRequest, CompanyExtractionResponse
from app.schemas.health import HealthResponse
from app.schemas.matching import EnterpriseMatchRequest, EnterpriseMatchResponse
from app.schemas.policy import PolicyQARequest, PolicyQAResponse
from app.services.company_extraction import extract_company_profile
from app.services.enterprise_matching import match_enterprises
from app.services.policy_qa import answer_policy_question

health_router = APIRouter(tags=["health"])
api_router = APIRouter()
SettingsDependency = Annotated[Settings, Depends(get_settings)]


def _ensure_unique_ids(values: list[str], code: str, message: str) -> None:
    canonical = [canonical_key(value) for value in values]
    if len(canonical) != len(set(canonical)):
        raise DomainError(code, message, status_code=409)


@health_router.get("/health", response_model=HealthResponse)
def health(settings: SettingsDependency) -> HealthResponse:
    return HealthResponse(
        service=settings.app_name,
        version=settings.version,
        environment=settings.environment,
        model_connected=settings.model_connected,
    )


@api_router.post(
    "/extract/company-profile",
    response_model=CompanyExtractionResponse,
    tags=["extraction"],
)
def extract_company(request: CompanyExtractionRequest) -> CompanyExtractionResponse:
    return extract_company_profile(request)


@api_router.post(
    "/match/enterprises",
    response_model=EnterpriseMatchResponse,
    responses={409: {"description": "候选企业标识冲突"}},
    tags=["matching"],
)
def match_enterprise_candidates(
    request: EnterpriseMatchRequest,
) -> EnterpriseMatchResponse:
    _ensure_unique_ids(
        [candidate.enterprise_id for candidate in request.candidates],
        "DUPLICATE_ENTERPRISE_ID",
        "候选企业标识 enterprise_id 不能重复",
    )
    if sum(request.weights.model_dump().values()) <= 0:
        raise DomainError(
            "INVALID_MATCHING_WEIGHTS",
            "匹配权重不能全部为0",
            status_code=409,
        )
    return match_enterprises(request)


@api_router.post(
    "/qa/policy",
    response_model=PolicyQAResponse,
    responses={409: {"description": "政策文档标识冲突"}},
    tags=["policy-qa"],
)
def policy_qa(request: PolicyQARequest) -> PolicyQAResponse:
    _ensure_unique_ids(
        [document.document_id for document in request.documents],
        "DUPLICATE_DOCUMENT_ID",
        "政策文档标识 document_id 不能重复",
    )
    return answer_policy_question(request)
