import pytest

from opik_optimizer.algorithms.evolutionary_optimizer import reporting


@pytest.mark.parametrize(
    "best_gen_score,expected",
    [
        (0.6, "No improvement"),  # a tie with the initial score is not a new prompt
        (0.7, "Found a new prompt"),
    ],
)
def test_end_gen_reports_new_prompt_only_on_strict_improvement(
    monkeypatch: pytest.MonkeyPatch, best_gen_score: float, expected: str
) -> None:
    lines: list[str] = []
    monkeypatch.setattr(
        reporting, "display_text_block", lambda text, **_kwargs: lines.append(text)
    )

    reporting.end_gen(1, best_gen_score, initial_primary_score=0.6, verbose=1)

    assert expected in lines[0]
