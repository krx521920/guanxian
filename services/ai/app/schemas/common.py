import unicodedata
from typing import Annotated

from pydantic import StringConstraints

Identifier = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=100,
        pattern=r".*\S.*",
    ),
]
LabelText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=200,
        pattern=r".*\S.*",
    ),
]
TitleText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=300,
        pattern=r".*\S.*",
    ),
]
SafeHttpUrl = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
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
