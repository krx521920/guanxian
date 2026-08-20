import re

from app.schemas.company import (
    CompanyExtractionRequest,
    CompanyExtractionResponse,
    CompanyProfile,
    ExtractionEvidence,
)
from app.services.text import fold, normalize, sentences, unique

ROLE_KEYWORDS = {
    "规划设计": ("规划", "设计", "咨询"),
    "勘察测绘": ("地勘", "勘察", "测绘", "探测"),
    "建设施工": ("施工", "建设", "安装", "工程总包"),
    "运营维护": ("运维", "维护", "检测", "修复", "应急"),
    "设备制造": ("生产", "制造", "设备", "阀门", "管材"),
    "软件与数字化": ("软件", "平台", "系统", "数字化", "人工智能", "智慧"),
}

SCENARIO_KEYWORDS = {
    "燃气": ("燃气", "天然气", "输气"),
    "供水排水": ("供水", "给水", "排水", "自来水", "污水"),
    "热力": ("热力", "供热", "热网"),
    "电力通信": ("电力", "电缆", "通信", "光缆"),
    "综合管廊": ("综合管廊", "管廊"),
    "矿山": ("矿山", "矿井"),
    "城市更新": ("城市更新", "老旧管网", "管网改造"),
}

PRODUCT_KEYWORDS = (
    "阀门",
    "球阀",
    "管材",
    "管道",
    "燃气报警器",
    "传感器",
    "检测设备",
    "监测系统",
    "管理平台",
    "数字孪生",
    "地理信息系统",
    "GIS",
)

QUALIFICATION_KEYWORDS = (
    "高新技术企业",
    "专精特新",
    "ISO9001",
    "ISO 9001",
    "测绘资质",
    "勘察资质",
    "施工总承包",
    "安全生产许可证",
)

REGION_KEYWORDS = (
    "全国",
    "北京",
    "天津",
    "河北",
    "雄安",
    "华北",
    "华东",
    "华南",
    "西北",
    "东北",
)


def _matched_labels(text: str, mapping: dict[str, tuple[str, ...]]) -> list[str]:
    normalized_text = normalize(text)
    return [
        label
        for label, keywords in mapping.items()
        if any(normalize(keyword) in normalized_text for keyword in keywords)
    ]


def _matched_terms(text: str, terms: tuple[str, ...]) -> list[str]:
    normalized_text = normalize(text)
    folded_text = fold(text)
    matches = [
        term
        for term in terms
        if fold(term) in folded_text and normalize(term) in normalized_text
    ]
    if not matches:
        matches = [term for term in terms if normalize(term) in normalized_text]

    result: list[str] = []
    seen: set[str] = set()
    for term in matches:
        key = normalize(term)
        if key not in seen:
            seen.add(key)
            result.append(term)
    return result


def _matched_keywords(
    text: str,
    mapping: dict[str, tuple[str, ...]],
    labels: list[str],
) -> list[str]:
    normalized_text = normalize(text)
    folded_text = fold(text)
    result: list[str] = []
    for label in labels:
        keywords = mapping[label]
        keyword = next(
            (
                item
                for item in keywords
                if fold(item) in folded_text and normalize(item) in normalized_text
            ),
            None,
        )
        if keyword is None:
            keyword = next(item for item in keywords if normalize(item) in normalized_text)
        result.append(keyword)
    return result


def _infer_company_name(text: str) -> str | None:
    match = re.search(
        r"([\u4e00-\u9fa5A-Za-z0-9（）()·]{2,40}(?:有限责任公司|股份有限公司|有限公司|研究院|协会))",
        text,
    )
    return match.group(1) if match else None


def _make_evidence(text: str, field: str, keywords: list[str]) -> list[ExtractionEvidence]:
    result: list[ExtractionEvidence] = []
    folded_text = fold(text)
    for keyword in keywords:
        position = folded_text.find(fold(keyword))
        if position < 0:
            continue
        start, end = max(position - 24, 0), min(position + len(keyword) + 24, len(text))
        result.append(
            ExtractionEvidence(
                field=field,
                keyword=keyword,
                excerpt=text[start:end].strip(),
            )
        )
    return result[:20]


def extract_company_profile(
    request: CompanyExtractionRequest,
) -> CompanyExtractionResponse:
    text = request.text
    roles = _matched_labels(text, ROLE_KEYWORDS)
    scenarios = _matched_labels(text, SCENARIO_KEYWORDS)
    role_keywords = _matched_keywords(text, ROLE_KEYWORDS, roles)
    scenario_keywords = _matched_keywords(text, SCENARIO_KEYWORDS, scenarios)
    products = _matched_terms(text, PRODUCT_KEYWORDS)
    qualifications = _matched_terms(text, QUALIFICATION_KEYWORDS)
    regions = _matched_terms(text, REGION_KEYWORDS)
    name = request.company_name or _infer_company_name(text)

    text_sentences = sentences(text)
    summary = (text_sentences[0] if text_sentences else text)[:300]
    tags = unique([*roles, *scenarios, *products])
    detected_groups = sum(
        bool(group)
        for group in (roles, scenarios, products, qualifications, regions)
    )
    confidence = round(min(0.35 + detected_groups * 0.11 + (0.1 if name else 0), 0.95), 2)
    evidence = [
        *_make_evidence(text, "business_roles", role_keywords),
        *_make_evidence(text, "scenarios", scenario_keywords),
        *_make_evidence(text, "products_services", products),
        *_make_evidence(text, "qualifications", qualifications),
        *_make_evidence(text, "service_regions", regions),
    ]
    warnings = []
    if not name:
        warnings.append("未识别企业名称，建议人工补充")
    if not products:
        warnings.append("未识别明确的产品或服务，建议人工复核")

    return CompanyExtractionResponse(
        profile=CompanyProfile(
            company_name=name,
            summary=summary,
            business_roles=roles,
            scenarios=scenarios,
            products_services=products,
            qualifications=qualifications,
            service_regions=regions,
            tags=tags,
        ),
        confidence=confidence,
        evidence=evidence,
        warnings=warnings,
    )
