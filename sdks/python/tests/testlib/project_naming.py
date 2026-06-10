"""Resource-name helper for the e2e test suite.

Project names are composed as ``prefix1-prefix2-...-random_chars`` so
re-running the suite against the same backend never reuses a name.

NOTE on pytest-xdist:
``random_chars`` is non-deterministic, so two xdist workers compute
different names for the same call. That is fine for module-level
constants used only inside test bodies (under ``--dist=loadfile`` each
file runs on a single worker, so other workers' copies of the constant
are never used). It is *not* fine for constants embedded in
``@pytest.mark.parametrize`` values: every worker collects every
parametrize id, and xdist refuses to run when ids differ across
workers. Use a plain string literal there instead.
"""

import secrets


def _random_chars(n: int = 6) -> str:
    # Duplicates tests/conftest.py::random_chars on purpose: importing
    # from tests/conftest.py here would create a cycle (conftest itself
    # imports from testlib). `secrets` (vs `random`) keeps per-worker
    # uniqueness independent of any seeding in the test environment
    # (e.g. a stray random.seed(...) in a fixture, or a pinned
    # PYTHONHASHSEED) — load-bearing for the xdist isolation contract.
    return secrets.token_hex((n + 1) // 2)[:n]


def generate_project_name(*prefixes: str) -> str:
    """Return ``prefix1-prefix2-...-random_chars``.

    Use at module top in any e2e test file that needs to reference its
    project — usually paired with ``__name__`` so the name embeds the
    test file::

        PROJECT_NAME = generate_project_name("e2e", __name__)

    The e2e ``configure_e2e_tests_env`` fixture reads ``PROJECT_NAME``
    from each test module and patches ``OPIK_PROJECT_NAME`` to that
    value, so the constant is the single source of truth.

    Dotted segments (e.g. ``__name__`` is ``tests.e2e.test_dataset``)
    are reduced to their last component so the resulting name stays a
    single hyphenated string.
    """
    parts = [prefix.rsplit(".", 1)[-1] for prefix in prefixes]
    parts.append(_random_chars())
    return "-".join(parts)
