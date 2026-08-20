from pydantic import BaseModel, Field, field_validator

from app.schemas.common import TitleText


class CompanyExtractionRequest(BaseModel):
    text: str = Field(
        min_length=5,
        max_length=50_000,
        pattern=r"^(?:\s*\S){5}[\s\S]*$",
        description="企业介绍原文",
    )
    company_name: TitleText | None = Field(
        default=None, max_length=200, description="可选的企业名称提示"
    )

    @field_validator("text", mode="before")
    @classmethod
    def text_must_not_be_blank(cls, value: object) -> object:
        if not isinstance(value, str):
            return value
        value = value.strip()
        if not value:
            raise ValueError("企业介绍不能为空")
        return value

class CompanyProfile(BaseModel):
    company_name: str | None = None
    summary: str
    business_roles: list[str] = Field(default_factory=list)
    scenarios: list[str] = Field(default_factory=list)
    products_services: list[str] = Field(default_factory=list)
    qualifications: list[str] = Field(default_factory=list)
    service_regions: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)


class ExtractionEvidence(BaseModel):
    field: str
    keyword: str
    excerpt: str


class CompanyExtractionResponse(BaseModel):
    profile: CompanyProfile
    confidence: float = Field(ge=0, le=1)
    evidence: list[ExtractionEvidence] = Field(default_factory=list)
    processing_mode: str = "deterministic_rules"
    warnings: list[str] = Field(default_factory=list)
