"""End-to-end test for ``opik migrate prompt`` against a real Opik backend.

Seeds a multi-version source prompt directly via the REST API, runs
``opik migrate prompt`` as a subprocess (exercising the actual Click
entrypoint + exit-code handling), then reads back the destination prompt
and asserts:

  - Destination has the same number of versions as the source
  - Each destination version carries the source commit hash verbatim
    (the architectural promise this slice exists to deliver)
  - Source has been renamed to ``<name>_v1``
  - Audit log is finalised to ``ok`` with one ``replay_prompt_version``
    record per source version
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator, List

import pytest

import opik
from opik.rest_api import OpikApi
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail

from ...conftest import random_chars
from ...testlib import generate_project_name
from .conftest import run_migrate_cli

PROJECT_NAME = generate_project_name("e2e", __name__)


@pytest.fixture
def prompt_name() -> Iterator[str]:
    yield f"e2e-migrate-prompt-{random_chars()}"


def _seed_source_prompt_with_versions(
    rest_client: OpikApi,
    *,
    name: str,
    project_name: str,
    version_specs: List[dict],
) -> List[str]:
    """Create a source prompt container plus N versions.

    First call to ``create_prompt`` carries the v1 template so the BE
    mints v1 with its own commit hash (we don't control v1's commit at
    seed time — that's fine for the test, we just need a multi-version
    history). Subsequent versions are minted via
    ``create_prompt_version`` so we control their commit + payload.

    Returns the list of source version commits in chronological order.
    """
    rest_client.prompts.create_prompt(
        name=name,
        project_name=project_name,
        description="e2e source description",
        tags=["e2e", "source"],
        template=version_specs[0]["template"],
        type=version_specs[0].get("type"),
        change_description=version_specs[0].get("change_description"),
    )
    versions_page = rest_client.prompts.get_prompts(name=name, size=10)
    source_row = versions_page.content[0]
    versions = rest_client.prompts.get_prompt_versions(
        id=source_row.id, page=1, size=100
    )
    v1_commit = versions.content[0].commit
    commits = [v1_commit]

    for spec in version_specs[1:]:
        version_payload = PromptVersionDetail(
            template=spec["template"],
            type=spec.get("type"),
            change_description=spec.get("change_description"),
            tags=spec.get("tags"),
        )
        created = rest_client.prompts.create_prompt_version(
            name=name,
            version=version_payload,
            project_name=project_name,
        )
        commits.append(created.commit)

    return commits


class TestMigratePromptE2E:
    def test_three_version_prompt_round_trips_with_commits_verbatim(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        prompt_name: str,
        tmp_path: Path,
    ) -> None:
        rest = opik_client.rest_client

        source_commits = _seed_source_prompt_with_versions(
            rest,
            name=prompt_name,
            project_name=source_project_name,
            version_specs=[
                {"template": "hi {{name}}", "type": "mustache"},
                {
                    "template": "hello {{name}}",
                    "type": "mustache",
                    "change_description": "be friendlier",
                },
                {
                    "template": "greetings {{name}}",
                    "type": "mustache",
                    "tags": ["polished"],
                },
            ],
        )

        audit_log_path = tmp_path / "audit.json"
        result = run_migrate_cli(
            ["prompt", prompt_name, "--to-project", target_project_name],
            audit_log_path=str(audit_log_path),
        )
        assert result.returncode == 0, (
            f"migrate prompt failed: stdout={result.stdout!r} stderr={result.stderr!r}"
        )

        # Source must have been renamed (workspace-unique name freed for
        # the destination to claim).
        renamed_page = rest.prompts.get_prompts(name=f"{prompt_name}_v1", size=10)
        assert renamed_page.content, (
            f"source prompt was not renamed to {prompt_name}_v1"
        )

        # Destination claims the original name.
        dest_page = rest.prompts.get_prompts(name=prompt_name, size=10)
        assert dest_page.content, "destination prompt not found at original name"
        dest_prompt = dest_page.content[0]

        # Every source version is replayed onto the destination with the
        # commit hash preserved verbatim. BE orders newest-first; reverse
        # to chronological for comparison against source_commits.
        dest_versions_page = rest.prompts.get_prompt_versions(
            id=dest_prompt.id, page=1, size=100
        )
        dest_commits = [v.commit for v in reversed(dest_versions_page.content or [])]
        assert dest_commits == source_commits

        # Audit log finalised to "ok" with one replay record per version.
        audit = json.loads(audit_log_path.read_text())
        assert audit["status"] == "ok"
        replay_records = [
            a for a in audit["actions"] if a["type"] == "replay_prompt_version"
        ]
        assert len(replay_records) == len(source_commits)
        assert all(a["status"] == "ok" for a in replay_records)
