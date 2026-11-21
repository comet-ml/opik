import pytest

from opik import semantic_version


@pytest.mark.parametrize(
    "lower,higher",
    [
        # SemVer 2.0.0 rule 11 examples, in precedence order.
        ("1.0.0-alpha", "1.0.0-alpha.1"),
        ("1.0.0-alpha.1", "1.0.0-alpha.beta"),
        ("1.0.0-alpha.beta", "1.0.0-beta"),
        ("1.0.0-beta", "1.0.0-beta.2"),
        ("1.0.0-beta.2", "1.0.0-beta.11"),
        ("1.0.0-beta.11", "1.0.0-rc.1"),
        ("1.0.0-rc.1", "1.0.0"),
        # A pre-release of a later core still sorts above an earlier release.
        ("0.9.10", "1.0.0-alpha"),
    ],
)
def test_parse__pre_release_precedence__orders_versions(lower: str, higher: str) -> None:
    assert semantic_version.SemanticVersion.parse(lower) < higher
    assert semantic_version.SemanticVersion.parse(higher) > lower
    assert not semantic_version.SemanticVersion.parse(lower) >= higher


def test_compare__release_and_its_pre_release__are_not_equal() -> None:
    assert semantic_version.SemanticVersion.parse("1.2.0-rc1") != "1.2.0"
    assert semantic_version.SemanticVersion.parse("1.2.0-rc1") < "1.2.0"


def test_compare__build_metadata__is_ignored() -> None:
    assert semantic_version.SemanticVersion.parse("1.2.0+build.1") == "1.2.0+build.2"
    assert not semantic_version.SemanticVersion.parse("1.2.0-rc1+build.1") > "1.2.0-rc1"
