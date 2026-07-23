import pytest
from opik import url_helpers
from opik.config import OpikConfig


@pytest.mark.parametrize(
    ("base_url", "expected_ping_url"),
    [
        (
            "http://localhost:5173/api",
            "http://localhost:5173/api/is-alive/ping",
        ),
        (
            "http://localhost:5173/api/",
            "http://localhost:5173/api/is-alive/ping",
        ),
        (
            "http://localhost:5173/api//",
            "http://localhost:5173/api/is-alive/ping",
        ),
        (
            "https://www.comet.com/opik/api",
            "https://www.comet.com/opik/api/is-alive/ping",
        ),
        (
            "https://www.comet.com/opik/api/",
            "https://www.comet.com/opik/api/is-alive/ping",
        ),
    ],
)
def test_get_is_alive_ping_url__base_url__returns_expected_ping_url(
    base_url: str, expected_ping_url: str
) -> None:
    assert url_helpers.get_is_alive_ping_url(base_url) == expected_ping_url


@pytest.mark.parametrize(
    ("url_override", "expected_ui_url"),
    [
        # Shipped defaults must keep working.
        ("http://localhost:5173/api", "http://localhost:5173/"),
        ("https://www.comet.com/opik/api/", "https://www.comet.com/opik/"),
        # Only the trailing "/api" segment is dropped: characters that merely
        # belong to the {/, a, p, i} set are no longer stripped one by one.
        ("https://foo.com/papi", "https://foo.com/papi/"),
        ("https://team.io/instance-pi/api", "https://team.io/instance-pi/"),
    ],
)
def test_get_ui_url__url_override__only_strips_api_suffix(
    url_override: str, expected_ui_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        url_helpers.opik.config,
        "OpikConfig",
        lambda: OpikConfig(url_override=url_override),
    )
    assert url_helpers.get_ui_url() == expected_ui_url


@pytest.mark.parametrize(
    ("base_url", "expected_dataset_url"),
    [
        (
            "http://localhost:5173/api",
            "http://localhost:5173/opik/my-workspace/projects/project-id/datasets/dataset-id/items",
        ),
        (
            "https://www.comet.com/opik/api/",
            "https://www.comet.com/opik/my-workspace/projects/project-id/datasets/dataset-id/items",
        ),
    ],
)
def test_get_dataset_url_by_id__returns_direct_project_scoped_url(
    base_url: str, expected_dataset_url: str
) -> None:
    assert (
        url_helpers.get_dataset_url_by_id(
            base_url=base_url,
            workspace="my-workspace",
            project_id="project-id",
            dataset_id="dataset-id",
        )
        == expected_dataset_url
    )


@pytest.mark.parametrize(
    ("base_url", "expected_experiment_url"),
    [
        (
            "http://localhost:5173/api",
            "http://localhost:5173/opik/my-workspace/experiments/dataset-id/compare?experiments=%5B%22experiment-id%22%5D",
        ),
        (
            "https://www.comet.com/opik/api/",
            "https://www.comet.com/opik/my-workspace/experiments/dataset-id/compare?experiments=%5B%22experiment-id%22%5D",
        ),
    ],
)
def test_get_experiment_url_by_id__returns_direct_workspace_scoped_url(
    base_url: str, expected_experiment_url: str
) -> None:
    assert (
        url_helpers.get_experiment_url_by_id(
            dataset_id="dataset-id",
            experiment_id="experiment-id",
            base_url=base_url,
            workspace="my-workspace",
        )
        == expected_experiment_url
    )


@pytest.mark.parametrize(
    ("base_url", "expected_test_suite_url"),
    [
        (
            "http://localhost:5173/api",
            "http://localhost:5173/opik/my-workspace/projects/project-id/test-suites/suite-id/items",
        ),
        (
            "https://www.comet.com/opik/api/",
            "https://www.comet.com/opik/my-workspace/projects/project-id/test-suites/suite-id/items",
        ),
    ],
)
def test_get_test_suite_url_by_id__returns_direct_project_scoped_url(
    base_url: str, expected_test_suite_url: str
) -> None:
    assert (
        url_helpers.get_test_suite_url_by_id(
            base_url=base_url,
            workspace="my-workspace",
            project_id="project-id",
            test_suite_id="suite-id",
        )
        == expected_test_suite_url
    )
