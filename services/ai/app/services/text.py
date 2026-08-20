import re
import unicodedata
from collections.abc import Iterable


def unique(items: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(item for item in items if item))


def sentences(text: str) -> list[str]:
    return [
        part.strip(" \t\r\n，,；;")
        for part in re.split(r"[。！？!?\n]+", text)
        if part.strip(" \t\r\n，,；;")
    ]


def fold(value: str) -> str:
    """做兼容大小写折叠，但保留分隔符，用于证据位置检索。"""

    return unicodedata.normalize("NFKC", value).casefold()


def normalize(value: str) -> str:
    value = fold(value)
    value = "".join(
        character
        for character in value
        if unicodedata.category(character) not in {"Cc", "Cf"}
    )
    value = re.sub(r"[\s，。；、,.;:：()（）/\\_-]+", "", value)
    return unicodedata.normalize("NFKC", value)


def term_set(values: Iterable[str]) -> set[str]:
    return {normalized for value in values if (normalized := normalize(value))}


def keyword_similarity(left: str, right: str) -> float:
    """用字符二元组计算确定性相似度，兼顾中文无空格文本。"""

    def grams(value: str) -> set[str]:
        value = normalize(value)
        if len(value) < 2:
            return {value} if value else set()
        return {value[index : index + 2] for index in range(len(value) - 1)}

    left_grams, right_grams = grams(left), grams(right)
    if not left_grams or not right_grams:
        return 0.0
    return len(left_grams & right_grams) / len(left_grams | right_grams)
