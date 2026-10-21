import pytest

from opik.evaluation.metrics.conversation.heuristics.knowledge_retention.metric import (
    KnowledgeRetentionMetric,
)
from opik.evaluation.metrics.score_result import ScoreResult


# ---------------------------------------------------------------------------
# A. Top-level branch coverage: "no assistant turns" (score 0.0).
# ---------------------------------------------------------------------------
_NO_ASSISTANT_TURNS_CASES = [
    ("empty conversation", []),
    (
        "only user turns",
        [{"role": "user", "content": "My account number is 12345."}],
    ),
    (
        "assistant turn with empty content",
        [
            {"role": "user", "content": "My account number is 12345."},
            {"role": "assistant", "content": ""},
        ],
    ),
    (
        "assistant turn missing content key",
        [
            {"role": "user", "content": "My account number is 12345."},
            {"role": "assistant"},
        ],
    ),
    (
        "role neither user nor assistant",
        [{"role": "system", "content": "You are a helpful bot."}],
    ),
]


@pytest.mark.parametrize(
    "conversation",
    [c for _, c in _NO_ASSISTANT_TURNS_CASES],
    ids=[label for label, _ in _NO_ASSISTANT_TURNS_CASES],
)
def test_no_assistant_turns_scores_zero(conversation):
    metric = KnowledgeRetentionMetric(track=False)

    assert metric.score(conversation=conversation) == ScoreResult(
        name=metric.name,
        value=0.0,
        reason="No assistant turns",
        metadata={},
    )


# ---------------------------------------------------------------------------
# A/B. "No facts to retain" (score 1.0): every user turn was filtered out
# before any facts could be extracted, either by being skipped for missing
# fields or by being classified as a request.
# ---------------------------------------------------------------------------
_NO_FACTS_TO_RETAIN_CASES = [
    (
        "only question",
        [
            {"role": "user", "content": "What is my balance?"},
            {"role": "assistant", "content": "Your balance is fine."},
        ],
    ),
    (
        "only request keyword, no question mark",
        [
            {"role": "user", "content": "Please help me today"},
            {"role": "assistant", "content": "Sure thing."},
        ],
    ),
    (
        "user turn missing content",
        [
            {"role": "user"},
            {"role": "assistant", "content": "OK."},
        ],
    ),
    (
        "user turn missing role",
        [
            {"content": "My account number is 12345."},
            {"role": "assistant", "content": "OK."},
        ],
    ),
]


@pytest.mark.parametrize(
    "conversation",
    [c for _, c in _NO_FACTS_TO_RETAIN_CASES],
    ids=[label for label, _ in _NO_FACTS_TO_RETAIN_CASES],
)
def test_no_facts_to_retain_scores_one(conversation):
    metric = KnowledgeRetentionMetric(track=False)

    assert metric.score(conversation=conversation) == ScoreResult(
        name=metric.name,
        value=1.0,
        reason="No facts to retain",
        metadata={},
    )


# ---------------------------------------------------------------------------
# B. User turns phrased as questions or requests are excluded from scoring.
# ---------------------------------------------------------------------------
def test_question_mark_suppresses_otherwise_rich_facts():
    """A user turn phrased as a question scores identically to one with no
    facts at all, even though it names the same real, specific terms as the
    statement version below. Phrasing the same content as a statement
    instead lets those terms count toward the score.
    """
    metric = KnowledgeRetentionMetric(track=False)

    as_question = [
        {"role": "user", "content": "What is my Netgear Nighthawk warranty status?"},
        {"role": "assistant", "content": "Your Netgear Nighthawk warranty is active."},
    ]
    assert metric.score(conversation=as_question) == ScoreResult(
        name=metric.name, value=1.0, reason="No facts to retain", metadata={}
    )

    as_statement = [
        {"role": "user", "content": "My Netgear Nighthawk warranty status matters."},
        {"role": "assistant", "content": "Your Netgear Nighthawk warranty is active."},
    ]
    result = metric.score(conversation=as_statement)
    assert result == ScoreResult(
        name=metric.name,
        value=0.6,
        reason="Retained 3 of 5 reference terms",
        metadata={
            "reference_terms": [
                "matters",
                "netgear",
                "nighthawk",
                "status",
                "warranty",
            ],
            "retained_terms": ["netgear", "nighthawk", "warranty"],
        },
    )


def test_request_keyword_suppresses_entire_turn():
    """A user turn phrased as a request ("please tell me...") scores as if
    it had no facts at all, even though it names the same specific terms
    as the plainly-stated version below - none of that turn's terms count
    toward the score, not just the request wording itself.
    """
    metric = KnowledgeRetentionMetric(track=False)

    with_keyword = [
        {
            "role": "user",
            "content": "Please tell me my Netgear Nighthawk serial number.",
        },
        {"role": "assistant", "content": "Your Netgear Nighthawk serial is confirmed."},
    ]
    assert metric.score(conversation=with_keyword) == ScoreResult(
        name=metric.name, value=1.0, reason="No facts to retain", metadata={}
    )

    without_keyword = [
        {"role": "user", "content": "My Netgear Nighthawk serial matters to me."},
        {"role": "assistant", "content": "Your Netgear Nighthawk serial is confirmed."},
    ]
    result = metric.score(conversation=without_keyword)
    assert result == ScoreResult(
        name=metric.name,
        value=0.75,
        reason="Retained 3 of 4 reference terms",
        metadata={
            "reference_terms": ["matters", "netgear", "nighthawk", "serial"],
            "retained_terms": ["netgear", "nighthawk", "serial"],
        },
    )


# Words that merely contain a request word as a substring - "cannot"
# ("can"), "shallow" ("shall"), "helpful" ("help") - do not cause their
# turn to be treated as a request. Verified empirically before writing
# this test: each turn below is scored as ordinary fact-bearing content,
# not excluded the way a genuine request turn would be.
_REQUEST_KEYWORD_SUBSTRING_NON_TRIGGER_CASES = [
    (
        "cannot",
        [
            {
                "role": "user",
                "content": "I cannot access my Netgear router settings.",
            },
            {
                "role": "assistant",
                "content": "Your Netgear router settings have been fixed.",
            },
        ],
        ScoreResult(
            name="knowledge_retention_metric",
            value=0.6,
            reason="Retained 3 of 5 reference terms",
            metadata={
                "reference_terms": [
                    "access",
                    "cannot",
                    "netgear",
                    "router",
                    "settings",
                ],
                "retained_terms": ["netgear", "router", "settings"],
            },
        ),
    ),
    (
        "shallow",
        [
            {
                "role": "user",
                "content": "My pool is shallow near the Netgear router.",
            },
            {
                "role": "assistant",
                "content": "Noted about the shallow area and Netgear router.",
            },
        ],
        ScoreResult(
            name="knowledge_retention_metric",
            value=0.6,
            reason="Retained 3 of 5 reference terms",
            metadata={
                "reference_terms": ["near", "netgear", "pool", "router", "shallow"],
                "retained_terms": ["netgear", "router", "shallow"],
            },
        ),
    ),
    (
        "helpful",
        [
            {
                "role": "user",
                "content": "My assistant was helpful about the Netgear router.",
            },
            {"role": "assistant", "content": "Noted about the Netgear router."},
        ],
        ScoreResult(
            name="knowledge_retention_metric",
            value=0.5,
            reason="Retained 2 of 4 reference terms",
            metadata={
                "reference_terms": ["assistant", "helpful", "netgear", "router"],
                "retained_terms": ["netgear", "router"],
            },
        ),
    ),
]


@pytest.mark.parametrize(
    "conversation,expected",
    [(c, e) for _, c, e in _REQUEST_KEYWORD_SUBSTRING_NON_TRIGGER_CASES],
    ids=[label for label, _, _ in _REQUEST_KEYWORD_SUBSTRING_NON_TRIGGER_CASES],
)
def test_request_keyword_substring_does_not_false_trigger(conversation, expected):
    metric = KnowledgeRetentionMetric(track=False)

    assert metric.score(conversation=conversation) == expected


def test_bare_request_keyword_does_trigger_exclusion():
    """Positive control paired with the substring test above: the exact
    token "can" (not a substring of a longer word) does exclude its turn.
    """
    metric = KnowledgeRetentionMetric(track=False)

    conversation = [
        {"role": "user", "content": "can you fix my Netgear router settings"},
        {
            "role": "assistant",
            "content": "Your Netgear router settings have been fixed.",
        },
    ]
    assert metric.score(conversation=conversation) == ScoreResult(
        name=metric.name, value=1.0, reason="No facts to retain", metadata={}
    )


# ---------------------------------------------------------------------------
# C. Which words in a turn count as facts: short words and common words are
# excluded from scoring.
# ---------------------------------------------------------------------------
def test_min_token_length_boundary_four_chars_kept_three_dropped():
    metric = KnowledgeRetentionMetric(track=False)

    four_char_terms_kept = [
        {"role": "user", "content": "My company is Acme corp."},
        {"role": "assistant", "content": "Acme corp noted."},
    ]
    result = metric.score(conversation=four_char_terms_kept)
    assert result == ScoreResult(
        name=metric.name,
        value=pytest.approx(2 / 3),
        reason="Retained 2 of 3 reference terms",
        metadata={
            "reference_terms": ["acme", "company", "corp"],
            "retained_terms": ["acme", "corp"],
        },
    )

    three_char_term_dropped = [
        {"role": "user", "content": "My cat is orange."},
        {"role": "assistant", "content": "cat noted"},
    ]
    result = metric.score(conversation=three_char_term_dropped)
    assert result == ScoreResult(
        name=metric.name,
        value=0.0,
        reason="Retained 0 of 1 reference terms",
        metadata={"reference_terms": ["orange"], "retained_terms": []},
    )


def test_stopword_filtered_even_when_long_enough_and_turn_not_a_request():
    """This user turn is plainly stated (no question, no request wording),
    so it is scored as ordinary fact-bearing content. Even so, the common
    word "number" - despite being long enough to otherwise qualify - never
    shows up among the scored terms, while the other, more specific words
    in the same sentence do.
    """
    metric = KnowledgeRetentionMetric(track=False)

    conversation = [
        {"role": "user", "content": "My favorite number is 42 and I live in Boston."},
        {"role": "assistant", "content": "Got it, favorite live boston noted."},
    ]
    result = metric.score(conversation=conversation)

    assert result == ScoreResult(
        name=metric.name,
        value=1.0,
        reason="Retained 3 of 3 reference terms",
        metadata={
            "reference_terms": ["boston", "favorite", "live"],
            "retained_terms": ["boston", "favorite", "live"],
        },
    )
    assert "number" not in result.metadata["reference_terms"]


# ---------------------------------------------------------------------------
# D. `turns_to_consider` slicing.
# ---------------------------------------------------------------------------
def _codeword_conversation(words):
    conversation = [
        {"role": "user", "content": f"My codeword is {word}."} for word in words
    ]
    conversation.append(
        {"role": "assistant", "content": " ".join(words) + " all noted"}
    )
    return conversation


def test_turns_to_consider_default_limits_reference_facts():
    """Default `turns_to_consider=5`: with 6 qualifying user turns
    available, the 6th turn's fact ("foxtrot") must not appear in
    `reference_terms` at all.
    """
    metric = KnowledgeRetentionMetric(track=False)
    conversation = _codeword_conversation(
        ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot"]
    )

    result = metric.score(conversation=conversation)

    assert "foxtrot" not in result.metadata["reference_terms"]
    assert result == ScoreResult(
        name=metric.name,
        value=pytest.approx(5 / 6),
        reason="Retained 5 of 6 reference terms",
        metadata={
            "reference_terms": [
                "alpha",
                "bravo",
                "charlie",
                "codeword",
                "delta",
                "echo",
            ],
            "retained_terms": ["alpha", "bravo", "charlie", "delta", "echo"],
        },
    )


def test_turns_to_consider_custom_small_value():
    metric = KnowledgeRetentionMetric(track=False, turns_to_consider=1)
    conversation = _codeword_conversation(
        ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot"]
    )

    result = metric.score(conversation=conversation)

    assert result == ScoreResult(
        name=metric.name,
        value=0.5,
        reason="Retained 1 of 2 reference terms",
        metadata={
            "reference_terms": ["alpha", "codeword"],
            "retained_terms": ["alpha"],
        },
    )


def test_turns_to_consider_zero_yields_no_facts_to_retain():
    """Boundary: with `turns_to_consider=0`, the conversation still has
    real, fact-bearing user turns, but the metric scores it exactly as if
    there were no facts to retain at all (1.0, reason "No facts to
    retain").
    """
    metric = KnowledgeRetentionMetric(track=False, turns_to_consider=0)
    conversation = _codeword_conversation(["alpha", "bravo"])

    assert metric.score(conversation=conversation) == ScoreResult(
        name=metric.name, value=1.0, reason="No facts to retain", metadata={}
    )


def test_turns_to_consider_slices_only_qualifying_turns():
    """A request-phrased turn placed between two fact-bearing turns is
    skipped rather than counted: with `turns_to_consider=2`, both
    fact-bearing turns' terms still show up in the score even though a
    request turn sits between them in the conversation.
    """
    metric = KnowledgeRetentionMetric(track=False, turns_to_consider=2)
    conversation = [
        {"role": "user", "content": "My codeword is alpha."},
        {"role": "user", "content": "Could you help me today?"},
        {"role": "user", "content": "My codeword is bravo."},
        {"role": "assistant", "content": "alpha bravo noted"},
    ]

    result = metric.score(conversation=conversation)

    assert result == ScoreResult(
        name=metric.name,
        value=pytest.approx(2 / 3),
        reason="Retained 2 of 3 reference terms",
        metadata={
            "reference_terms": ["alpha", "bravo", "codeword"],
            "retained_terms": ["alpha", "bravo"],
        },
    )


# ---------------------------------------------------------------------------
# E. Only the final assistant turn is scored.
# ---------------------------------------------------------------------------
def test_only_final_assistant_turn_is_scored():
    """An earlier assistant reply that correctly references the fact does
    not affect the score: only the most recent assistant reply is checked,
    and here it omits the fact entirely.
    """
    metric = KnowledgeRetentionMetric(track=False)
    conversation = [
        {"role": "user", "content": "My codeword is alpha."},
        {"role": "assistant", "content": "alpha noted!"},
        {"role": "user", "content": "Thanks."},
        {"role": "assistant", "content": "You are welcome."},
    ]

    result = metric.score(conversation=conversation)

    assert result == ScoreResult(
        name=metric.name,
        value=0.0,
        reason="Retained 0 of 3 reference terms",
        metadata={
            "reference_terms": ["alpha", "codeword", "thanks"],
            "retained_terms": [],
        },
    )


# ---------------------------------------------------------------------------
# F. Retention-ratio boundary values: a score of 1.0 from full retention
# reads differently (reason, metadata) than a score of 1.0 from having no
# facts to retain at all, even though the numeric value is the same.
# ---------------------------------------------------------------------------
def test_retention_ratio_can_reach_exact_zero_and_exact_one_via_real_computation():
    metric = KnowledgeRetentionMetric(track=False)

    zero_case = metric.score(
        conversation=[
            {"role": "user", "content": "My cat is orange."},
            {"role": "assistant", "content": "cat noted"},
        ]
    )
    assert zero_case.value == 0.0
    assert zero_case.reason == "Retained 0 of 1 reference terms"
    assert zero_case.metadata != {}

    one_case = metric.score(
        conversation=[
            {
                "role": "user",
                "content": "My favorite number is 42 and I live in Boston.",
            },
            {"role": "assistant", "content": "Got it, favorite live boston noted."},
        ]
    )
    assert one_case.value == 1.0
    assert one_case.reason == "Retained 3 of 3 reference terms"
    assert one_case.metadata != {}

    # Contrast with the OTHER way to get 1.0 (no facts at all), which must
    # be distinguishable by reason/metadata even though the value is equal.
    no_facts_case = metric.score(
        conversation=[
            {"role": "user", "content": "What is my balance?"},
            {"role": "assistant", "content": "Your balance is fine."},
        ]
    )
    assert no_facts_case.value == 1.0
    assert no_facts_case.reason == "No facts to retain"
    assert no_facts_case.metadata == {}
    assert no_facts_case.reason != one_case.reason


# ---------------------------------------------------------------------------
# G. Text normalization interactions.
# ---------------------------------------------------------------------------
def test_emoji_is_stripped_without_leaving_a_stray_token():
    metric = KnowledgeRetentionMetric(track=False)
    conversation = [
        {"role": "user", "content": "My favorite emoji is 😊 and I love Boston."},
        {"role": "assistant", "content": "Noted: favorite love boston"},
    ]

    result = metric.score(conversation=conversation)

    assert result == ScoreResult(
        name=metric.name,
        value=0.75,
        reason="Retained 3 of 4 reference terms",
        metadata={
            "reference_terms": ["boston", "emoji", "favorite", "love"],
            "retained_terms": ["boston", "favorite", "love"],
        },
    )


def test_punctuation_removal_merges_hyphenated_compound_terms():
    """Documents existing behavior, not fixed by this test-only PR.

    The normalizer used here (`normalize_text(..., remove_punctuation=True)`)
    strips punctuation characters without inserting a replacement space
    (`str.translate` deletion, not substitution). A hyphenated compound like
    "Netgear-Nighthawk" therefore collapses into the single merged token
    "netgearnighthawk", which then fails to match the assistant's own
    "Netgear Nighthawk" (two separate space-separated tokens) - a false
    "forgotten fact" result even though the assistant clearly referenced
    the exact same router by name. Worth a follow-up issue, but out of
    scope for a tests-only PR.
    """
    metric = KnowledgeRetentionMetric(track=False)
    conversation = [
        {"role": "user", "content": "My router is Netgear-Nighthawk."},
        {"role": "assistant", "content": "Your Netgear Nighthawk is configured."},
    ]

    result = metric.score(conversation=conversation)

    assert result == ScoreResult(
        name=metric.name,
        value=0.0,
        reason="Retained 0 of 2 reference terms",
        metadata={
            "reference_terms": ["netgearnighthawk", "router"],
            "retained_terms": [],
        },
    )


# ---------------------------------------------------------------------------
# H. Standard BaseMetric surface (mirrors test_is_json.py conventions).
# ---------------------------------------------------------------------------
def test_custom_name_is_used():
    metric = KnowledgeRetentionMetric(name="my_custom_retention_check", track=False)

    assert metric.name == "my_custom_retention_check"

    result = metric.score(
        conversation=[
            {"role": "user", "content": "My codeword is alpha."},
            {"role": "assistant", "content": "alpha codeword noted"},
        ]
    )
    assert result.name == "my_custom_retention_check"
    assert result.value == 1.0


def test_ignored_kwargs_are_accepted():
    metric = KnowledgeRetentionMetric(track=False)

    result = metric.score(
        conversation=[
            {"role": "user", "content": "My codeword is alpha."},
            {"role": "assistant", "content": "alpha codeword noted"},
        ],
        some_extra_arg="extra_value",
    )

    assert result.value == 1.0


def test_public_api_import_path():
    from opik.evaluation.metrics import KnowledgeRetentionMetric as PublicMetric

    metric = PublicMetric(track=False)
    result = metric.score(
        conversation=[
            {"role": "user", "content": "My codeword is alpha."},
            {"role": "assistant", "content": "alpha codeword noted"},
        ]
    )
    assert result.value == 1.0
