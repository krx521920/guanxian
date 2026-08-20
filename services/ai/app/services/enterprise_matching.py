from app.schemas.matching import (
    DemandProfile,
    EnterpriseCandidate,
    EnterpriseMatch,
    EnterpriseMatchRequest,
    EnterpriseMatchResponse,
    MatchingWeights,
    ScoreBreakdown,
)
from app.services.text import normalize, term_set


def _coverage(required: list[str], actual: list[str], fallback_full: bool = True) -> float:
    required_terms = term_set(required)
    if not required_terms:
        return 1.0 if fallback_full else 0.0
    actual_terms = term_set(actual)
    if not actual_terms:
        return 0.0
    matched = sum(
        any(expected in value or value in expected for value in actual_terms)
        for expected in required_terms
    )
    return matched / len(required_terms)


def _region_score(region: str | None, service_regions: list[str]) -> float:
    if not region:
        return 1.0
    wanted = normalize(region)
    regions = term_set(service_regions)
    if normalize("全国") in regions:
        return 1.0
    if any(wanted in item or item in wanted for item in regions):
        return 1.0
    return 0.0


def _data_quality(candidate: EnterpriseCandidate) -> float:
    freshness = max(0.0, 1 - candidate.updated_days_ago / 365)
    return candidate.data_completeness * 0.7 + freshness * 0.3


def _score_breakdown(raw_scores: dict[str, float]) -> ScoreBreakdown:
    """以分为粒度输出分项分，并确保分项之和与总分完全一致。"""

    rounded = {name: round(value, 2) for name, value in raw_scores.items()}
    target_total = round(min(max(sum(raw_scores.values()), 0.0), 100.0), 2)
    rounding_drift = round(sum(rounded.values()) - target_total, 2)
    if rounding_drift:
        adjustment_field = max(rounded, key=rounded.__getitem__)
        rounded[adjustment_field] = round(
            rounded[adjustment_field] - rounding_drift,
            2,
        )
    return ScoreBreakdown(**rounded)


def _score_candidate(
    demand: DemandProfile,
    candidate: EnterpriseCandidate,
    weights: MatchingWeights,
) -> EnterpriseMatch:
    scenario_ratio = _coverage(demand.scenarios, candidate.scenarios)
    capability_targets = demand.required_capabilities or [demand.title]
    candidate_capabilities = [*candidate.capabilities, candidate.description]
    capability_ratio = _coverage(capability_targets, candidate_capabilities)
    qualification_ratio = _coverage(
        demand.required_qualifications, candidate.qualifications
    )
    case_ratio = min(candidate.case_count / 3, 1)
    region_ratio = _region_score(demand.region, candidate.service_regions)
    quality_ratio = _data_quality(candidate)

    breakdown = _score_breakdown(
        {
            "scenario": scenario_ratio * weights.scenario,
            "capability": capability_ratio * weights.capability,
            "qualification": qualification_ratio * weights.qualification,
            "case": case_ratio * weights.case,
            "region": region_ratio * weights.region,
            "data_quality": quality_ratio * weights.data_quality,
        }
    )
    total = round(sum(breakdown.model_dump().values()), 2)
    missing_qualifications = [
        item
        for item in demand.required_qualifications
        if _coverage([item], candidate.qualifications) == 0
    ]
    missing_conditions: list[str] = []
    if missing_qualifications:
        missing_conditions.append("缺少要求资质：" + "、".join(missing_qualifications))
    if demand.region and region_ratio == 0:
        missing_conditions.append(f"未声明可服务地区：{demand.region}")
    if scenario_ratio == 0 and demand.scenarios:
        missing_conditions.append("应用场景未匹配")

    reasons = []
    if scenario_ratio > 0:
        reasons.append(f"场景匹配度 {scenario_ratio:.0%}")
    if capability_ratio > 0:
        reasons.append(f"能力匹配度 {capability_ratio:.0%}")
    if qualification_ratio == 1 and demand.required_qualifications:
        reasons.append("满足全部必需资质")
    if candidate.case_count:
        reasons.append(f"已登记 {candidate.case_count} 个案例")
    if region_ratio == 1 and demand.region:
        reasons.append(f"可服务 {demand.region}")

    return EnterpriseMatch(
        enterprise_id=candidate.enterprise_id,
        enterprise_name=candidate.enterprise_name,
        score=total,
        eligible=not missing_qualifications,
        breakdown=breakdown,
        reasons=reasons or ["基础资料参与匹配，暂无突出匹配项"],
        missing_conditions=missing_conditions,
    )


def match_enterprises(request: EnterpriseMatchRequest) -> EnterpriseMatchResponse:
    matches = [
        _score_candidate(request.demand, candidate, request.weights)
        for candidate in request.candidates
    ]
    matches.sort(key=lambda item: (-int(item.eligible), -item.score, item.enterprise_id))
    return EnterpriseMatchResponse(
        demand_id=request.demand.demand_id,
        matches=matches[: request.top_k],
        total_candidates=len(request.candidates),
    )
