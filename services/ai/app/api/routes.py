from typing import Annotated

from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.errors import DomainError
from app.schemas.common import canonical_key
from app.schemas.company import CompanyExtractionRequest, CompanyExtractionResponse
from app.schemas.health import HealthResponse
from app.schemas.matching import EnterpriseMatchRequest, EnterpriseMatchResponse
from app.schemas.policy import PolicyQARequest
from app.services.company_extraction import extract_company_profile
from app.services.enterprise_matching import match_enterprises

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
    status_code=503,
    responses={503: {"description": "政策问答能力未在 Python 服务启用"}},
    tags=["policy-qa"],
    summary="政策问答兼容入口（未启用）",
)
def policy_qa(_: PolicyQARequest) -> None:
    raise DomainError(
        "POLICY_QA_NOT_ENABLED",
        "Python 服务未启用政策问答，请使用 Java ai-adapter 的知识库问答接口",
        status_code=503,
    )
