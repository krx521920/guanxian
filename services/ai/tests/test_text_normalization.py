import unicodedata

from hypothesis import given
from hypothesis import strategies as st

from app.services.text import keyword_similarity, normalize, term_set


def test_normalize_handles_full_width_case_spacing_and_invisible_controls():
    assert normalize(" ＧＩＳ\u200b（北 京） ") == "gis北京"
    assert term_set(["ＩＳＯ９００１", "iso9001"]) == {"iso9001"}
    assert keyword_similarity("ＧＩＳ 管线", "gis管线") == 1


@given(value=st.text(max_size=500))
def test_normalize_is_idempotent_and_never_contains_control_characters(value):
    normalized = normalize(value)

    assert normalize(normalized) == normalized
    assert all(
        unicodedata.category(character) not in {"Cc", "Cf"}
        for character in normalized
    )
