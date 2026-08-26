import unicodedata
from urllib.parse import urlsplit

from pydantic import BaseModel, Field, field_validator

from app.schemas.common import Identifier, SafeHttpUrl, TitleText


class PolicyDocument(BaseModel):
    document_id: Identifier
    title: TitleText
    content: str = Field(min_length=1, max_length=100_000, pattern=r".*\S.*")
    source_url: SafeHttpUrl | None = None

    @field_validator("content", mode="before")
    @classmethod
    def content_must_not_be_blank(cls, value: object) -> object:
        if isinstance(value, str) and not value.strip():
            raise ValueError("政策文档内容不能为空")
        return value

    @field_validator("source_url", mode="before")
    @classmethod
    def source_url_must_be_http(cls, value: object) -> object:
        if value is None or not isinstance(value, str):
            return value
        if len(value) > 2_000:
            raise ValueError("政策来源地址不能超过2000个字符")
        value = value.strip()
        parsed = urlsplit(value)
        has_unsafe_character = "\\" in value or any(
            character.isspace()
            or unicodedata.category(character) in {"Cc", "Cf"}
            for character in value
        )
        try:
            hostname = parsed.hostname
            _ = parsed.port
        except ValueError:
            hostname = None
        if (
            has_unsafe_character
            or parsed.scheme.casefold() not in {"http", "https"}
            or not hostname
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise ValueError("政策来源地址必须是有效的 HTTP(S) URL")
        return value


class PolicyQARequest(BaseModel):
    question: str = Field(
        min_length=2,
        max_length=2_000,
        pattern=r"^(?:\s*\S){2}[\s\S]*$",
    )
    documents: list[PolicyDocument] = Field(default_factory=list, max_length=50)
    top_k: int = Field(default=3, ge=1, le=10)

    @field_validator("question", mode="before")
    @classmethod
    def question_must_not_be_blank(cls, value: object) -> object:
        if not isinstance(value, str):
            return value
        if len(value) > 2_000:
            raise ValueError("问题不能超过2000个字符")
        value = value.strip()
        if not value:
            raise ValueError("问题不能为空")
        return value

    @field_validator("top_k", mode="before")
    @classmethod
    def top_k_must_be_json_integer(cls, value: object) -> object:
        if isinstance(value, bool):
            raise ValueError("布尔值不能作为整数")
        if isinstance(value, float) and value.is_integer():
            return int(value)
        return value

class PolicyCitation(BaseModel):
    document_id: str
    title: str
    excerpt: str
    relevance_score: float = Field(ge=0, le=1)
    source_url: str | None = None


class PolicyQAResponse(BaseModel):
    answer: str
    citations: list[PolicyCitation] = Field(default_factory=list)
    model_connected: bool = False
    processing_mode: str = "placeholder_retrieval"
    warning: str
