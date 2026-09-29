"""Pins the tiktoken behaviour this image's token accounting depends on.

tiktoken is a transitive pin — litellm requires it — on a 0.x version line, so a
minor bump changes tokenization with no major-version signal to review against.
Reported prompt and completion usage, and the cost derived from it, flows
through these encodings, so a silent change in the BPE data or in the
model-to-encoding mapping would move numbers users see without failing anything.

Loading an encoding fetches its BPE data from openaipublic.blob.core.windows.net
on first use; nothing is baked into the image and no cache directory is
configured. The data-dependent tests therefore skip rather than fail when that
host is unreachable, so an egress blip cannot turn into a red build. The mapping
tests above them need no network.
"""

import pytest
import tiktoken
from requests.exceptions import RequestException

# The only two encodings litellm selects for OpenAI-family models.
REQUIRED_ENCODINGS = ("cl100k_base", "o200k_base")

SAMPLES = {
    "ascii": "Evaluate the following output for hallucinations.",
    "unicode": "Привет, мир! 你好世界 🎉 café naïve",
    "json": '{"input": {"question": "What is 2+2?"}, "output": "4"}',
    "code": "def score(output: str) -> float:\n    return 1.0 if output else 0.0\n",
    "whitespace": "a  b\t\tc\n\nd    e",
    "numbers": "1234567890 3.14159 1e-9 0xFF",
    "repeat": "token " * 40,
    "empty": "",
}

EXPECTED_COUNTS = {
    "cl100k_base": {
        "ascii": 8, "unicode": 19, "json": 21, "code": 23,
        "whitespace": 9, "numbers": 17, "repeat": 41, "empty": 0,
    },
    "o200k_base": {
        "ascii": 8, "unicode": 13, "json": 21, "code": 23,
        "whitespace": 9, "numbers": 18, "repeat": 41, "empty": 0,
    },
}

# Exact ids, not just a length: a change to the vocabulary or the merge table
# can preserve the count while remapping tokens.
EXPECTED_IDS = {
    "cl100k_base": [15339, 1200, 1609],
    "o200k_base": [24912, 991, 507],
}


@pytest.fixture(scope="module")
def encodings():
    try:
        return {name: tiktoken.get_encoding(name) for name in REQUIRED_ENCODINGS}
    except RequestException as exc:
        pytest.skip(f"tiktoken BPE data is unreachable, skipping data-dependent checks: {exc}")


def test_required_encodings_are_registered():
    assert set(REQUIRED_ENCODINGS).issubset(tiktoken.list_encoding_names())


@pytest.mark.parametrize(
    ("model", "expected"),
    [
        ("gpt-3.5-turbo", "cl100k_base"),
        ("gpt-4", "cl100k_base"),
        ("gpt-4-turbo", "cl100k_base"),
        ("text-embedding-3-small", "cl100k_base"),
        ("gpt-4o", "o200k_base"),
        ("gpt-4o-mini", "o200k_base"),
        ("o1", "o200k_base"),
        ("o3-mini", "o200k_base"),
    ],
)
def test_model_maps_to_expected_encoding(model, expected):
    assert tiktoken.encoding_name_for_model(model) == expected


@pytest.mark.parametrize("model", ["gpt-4.1", "o4-mini", "gpt-5"])
def test_recent_model_families_resolve(model):
    """These raised KeyError until tiktoken 0.12; a downgrade would reintroduce it."""
    assert tiktoken.encoding_name_for_model(model) == "o200k_base"


@pytest.mark.parametrize("name", REQUIRED_ENCODINGS)
def test_token_ids_are_stable(encodings, name):
    assert encodings[name].encode("hello opik") == EXPECTED_IDS[name]


@pytest.mark.parametrize("name", REQUIRED_ENCODINGS)
@pytest.mark.parametrize("sample", sorted(SAMPLES))
def test_token_counts_are_stable(encodings, name, sample):
    assert len(encodings[name].encode(SAMPLES[sample])) == EXPECTED_COUNTS[name][sample]


@pytest.mark.parametrize("name", REQUIRED_ENCODINGS)
def test_roundtrip_preserves_content(encodings, name):
    encoding = encodings[name]
    for text in SAMPLES.values():
        assert encoding.decode(encoding.encode(text)) == text


@pytest.mark.parametrize("name", REQUIRED_ENCODINGS)
def test_control_tokens_in_content_are_not_silently_encoded(encodings, name):
    """Scored payloads are user-controlled, so a literal <|endoftext|> must stay text.

    Honouring it as a control token would let scored content truncate the
    tokenizer's view of a prompt.
    """
    encoding = encodings[name]
    marker = "<|endoftext|>"

    with pytest.raises(ValueError):
        encoding.encode(marker)

    assert encoding.encode_ordinary(marker) != [encoding.eot_token]
    assert encoding.encode(marker, allowed_special={marker}) == [encoding.eot_token]


def test_litellm_reported_token_counts_are_stable(encodings):
    """The seam that reaches users: usage reported for a scoring call.

    Guards both halves of the chain — a tiktoken bump changing tokenization and
    a litellm bump changing which tokenizer it selects.
    """
    import litellm

    text = "Evaluate the following output for hallucinations: the capital of France is Paris."
    messages = [
        {"role": "system", "content": "You are a judge."},
        {"role": "user", "content": text},
    ]

    for model in ("gpt-4o", "gpt-4", "gpt-3.5-turbo"):
        assert litellm.token_counter(model=model, text=text) == 15
        assert litellm.token_counter(model=model, messages=messages) == 31
