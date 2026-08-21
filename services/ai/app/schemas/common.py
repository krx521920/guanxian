import unicodedata
from typing import Annotated

from pydantic import BeforeValidator, StringConstraints


def _strip_bounded_string(value: object, max_length: int) -> object:
    """Reject an oversized wire value before trimming it.

    JSON Schema maxLength applies to the value received on the wire. Pydantic's
    strip_whitespace runs before its length constraint, which otherwise lets an
    overlong value pass when the excess consists of surrounding whitespace.
    """

    if isinstance(value, str):
        if len(value) > max_length:
            raise ValueError(f"字符串长度不能超过 {max_length}")
        return value.strip()
    return value


def _strip_identifier(value: object) -> object:
    return _strip_bounded_string(value, 100)


def _strip_label(value: object) -> object:
    return _strip_bounded_string(value, 200)


def _strip_title(value: object) -> object:
    return _strip_bounded_string(value, 300)


def _strip_url(value: object) -> object:
    return _strip_bounded_string(value, 2_000)


Identifier = Annotated[
    str,
    BeforeValidator(_strip_identifier),
    StringConstraints(
        min_length=1,
        max_length=100,
        pattern=r".*\S.*",
    ),
]
LabelText = Annotated[
    str,
    BeforeValidator(_strip_label),
    StringConstraints(
        min_length=1,
        max_length=200,
        pattern=r".*\S.*",
    ),
]
TitleText = Annotated[
    str,
    BeforeValidator(_strip_title),
    StringConstraints(
        min_length=1,
        max_length=300,
        pattern=r".*\S.*",
    ),
]
SafeHttpUrl = Annotated[
    str,
    BeforeValidator(_strip_url),
    StringConstraints(
        min_length=8,
        max_length=2_000,
        pattern=r"^https?://[A-Za-z0-9._~%\-]+(?::[0-9]{1,5})?(?:[/?#][^\s\\@]*)?$",
    ),
]


def canonical_key(value: str) -> str:
    """为标识符和标签生成稳定比较键，不改写对外输出。"""

    return unicodedata.normalize("NFKC", value).casefold()


def unique_strings(values: list[str]) -> list[str]:
    """按 Unicode 兼容等价关系去重，并保留首次出现的顺序。"""

    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        key = canonical_key(value)
        if key not in seen:
            seen.add(key)
            result.append(value)
    return result
