from pydantic import BaseModel, Field, field_validator, model_validator

from app.schemas.common import (
    Identifier,
    LabelText,
    TitleText,
    unique_strings,
)


def _normalize_json_integer(value: object) -> object:
    if isinstance(value, bool):
        raise ValueError("布尔值不能作为整数")
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


class DemandProfile(BaseModel):
    demand_id: Identifier
    title: TitleText
    description: str = Field(default="", max_length=10_000)
    scenarios: list[LabelText] = Field(default_factory=list, max_length=50)
    required_capabilities: list[LabelText] = Field(default_factory=list, max_length=50)
    required_qualifications: list[LabelText] = Field(default_factory=list, max_length=50)
    region: LabelText | None = None

    @field_validator(
        "scenarios", "required_capabilities", "required_qualifications", mode="after"
    )
    @classmethod
    def list_values_are_unique(cls, value: list[str]) -> list[str]:
        return unique_strings(value)


class EnterpriseCandidate(BaseModel):
    enterprise_id: Identifier
    enterprise_name: TitleText
    description: str = Field(default="", max_length=10_000)
    scenarios: list[LabelText] = Field(default_factory=list, max_length=50)
    capabilities: list[LabelText] = Field(default_factory=list, max_length=50)
    qualifications: list[LabelText] = Field(default_factory=list, max_length=50)
    service_regions: list[LabelText] = Field(default_factory=list, max_length=50)
    case_count: int = Field(default=0, ge=0, le=1_000_000)
    data_completeness: float = Field(default=0.5, ge=0, le=1, strict=True)
    updated_days_ago: int = Field(default=365, ge=0, le=36_500)

    @field_validator("case_count", "updated_days_ago", mode="before")
    @classmethod
    def numeric_fields_must_be_json_integers(cls, value: object) -> object:
        return _normalize_json_integer(value)

    @field_validator(
        "scenarios", "capabilities", "qualifications", "service_regions", mode="after"
    )
    @classmethod
    def list_values_are_unique(cls, value: list[str]) -> list[str]:
        return unique_strings(value)


class MatchingWeights(BaseModel):
    scenario: float = Field(default=35, ge=0, le=100, strict=True)
    capability: float = Field(default=25, ge=0, le=100, strict=True)
    qualification: float = Field(default=15, ge=0, le=100, strict=True)
    case: float = Field(default=10, ge=0, le=100, strict=True)
    region: float = Field(default=10, ge=0, le=100, strict=True)
    data_quality: float = Field(default=5, ge=0, le=100, strict=True)

    @model_validator(mode="after")
    def normalize_relative_weights(self) -> "MatchingWeights":
        total = sum(
            (
                self.scenario,
                self.capability,
                self.qualification,
                self.case,
                self.region,
                self.data_quality,
            )
        )
        if total > 0 and abs(total - 100) > 0.001:
            scale = 100 / total
            for field_name in type(self).model_fields:
                setattr(self, field_name, getattr(self, field_name) * scale)
        return self


class EnterpriseMatchRequest(BaseModel):
    demand: DemandProfile
    candidates: list[EnterpriseCandidate] = Field(min_length=1, max_length=500)
    top_k: int = Field(default=10, ge=1, le=100)
    weights: MatchingWeights = Field(default_factory=MatchingWeights)

    @field_validator("top_k", mode="before")
    @classmethod
    def top_k_must_be_json_integer(cls, value: object) -> object:
        return _normalize_json_integer(value)

class ScoreBreakdown(BaseModel):
    scenario: float
    capability: float
    qualification: float
    case: float
    region: float
    data_quality: float


class EnterpriseMatch(BaseModel):
    enterprise_id: str
    enterprise_name: str
    score: float = Field(ge=0, le=100)
    eligible: bool
    breakdown: ScoreBreakdown
    reasons: list[str] = Field(default_factory=list)
    missing_conditions: list[str] = Field(default_factory=list)


class EnterpriseMatchResponse(BaseModel):
    demand_id: str
    matches: list[EnterpriseMatch]
    total_candidates: int
    algorithm_version: str = "rules-v1"
    processing_mode: str = "deterministic_rules"
