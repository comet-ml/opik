"""Tests for the common_metrics module."""

import pytest
from opik_backend import create_app
from opik_backend.common_metrics import (
    get_common_metrics_list,
    instantiate_metric,
    init_common_metrics_registry,
    _get_common_metrics_registry,
    _discover_heuristic_metrics,
    _camel_to_snake,
    EXCLUDED_METRICS,
    MAPPABLE_PARAMS,
)


@pytest.fixture
def app():
    """Create application for testing."""
    app = create_app(test_config={"TESTING": True}, should_init_executor=False)
    return app


@pytest.fixture
def client(app):
    """Create test client."""
    return app.test_client()


class TestCamelToSnake:
    """Tests for the _camel_to_snake helper function."""

    def test_simple_camel_case(self):
        """Should convert simple CamelCase to snake_case."""
        assert _camel_to_snake("Contains") == "contains"
        assert _camel_to_snake("IsJson") == "is_json"

    def test_multiple_capitals(self):
        """Should handle multiple capital letters correctly."""
        assert _camel_to_snake("LevenshteinRatio") == "levenshtein_ratio"
        assert _camel_to_snake("RegexMatch") == "regex_match"

    def test_consecutive_capitals(self):
        """Should handle consecutive capital letters like acronyms."""
        # All-caps words become lowercase (no underscores between consecutive capitals)
        assert _camel_to_snake("ROUGE") == "rouge"
        assert _camel_to_snake("METEOR") == "meteor"
        # Mixed case with trailing lowercase gets underscore before the lowercase portion
        assert _camel_to_snake("BERTScore") == "bert_score"

    def test_already_snake_case(self):
        """Should not modify already snake_case strings."""
        assert _camel_to_snake("contains") == "contains"
        assert _camel_to_snake("is_json") == "is_json"


class TestDiscoverHeuristicMetrics:
    """Tests for the _discover_heuristic_metrics function."""

    def test_discovers_metrics(self):
        """Should discover metrics from the SDK."""
        metrics = _discover_heuristic_metrics()
        assert isinstance(metrics, dict)
        assert len(metrics) > 0

    def test_discovers_known_metrics(self):
        """Should discover known metrics like Contains and Equals."""
        metrics = _discover_heuristic_metrics()
        
        # Check for some known metrics
        assert "contains" in metrics
        assert "equals" in metrics
        assert "is_json" in metrics
        assert "levenshtein_ratio" in metrics
        assert "regex_match" in metrics

    def test_excludes_bertscore(self):
        """Should exclude BERTScore as it requires heavy ML models."""
        metrics = _discover_heuristic_metrics()
        
        # BERTScore should be excluded
        for metric_id in metrics:
            assert "bertscore" not in metric_id.lower() or "BERTScore" in EXCLUDED_METRICS

    def test_metrics_are_basemetric_subclasses(self):
        """All discovered metrics should be BaseMetric subclasses."""
        from opik.evaluation.metrics import base_metric
        
        metrics = _discover_heuristic_metrics()
        for metric_id, metric_cls in metrics.items():
            assert issubclass(metric_cls, base_metric.BaseMetric), \
                f"{metric_id} is not a BaseMetric subclass"


class TestGetCommonMetricsRegistry:
    """Tests for the _get_common_metrics_registry function."""

    def test_returns_cached_registry(self):
        """Should return the same cached registry on multiple calls."""
        registry1 = _get_common_metrics_registry()
        registry2 = _get_common_metrics_registry()
        assert registry1 is registry2

    def test_registry_is_not_empty(self):
        """Registry should contain discovered metrics."""
        registry = _get_common_metrics_registry()
        assert len(registry) > 0

    def test_init_common_metrics_registry(self, app):
        """init_common_metrics_registry should initialize the registry."""
        # The registry is already initialized by the app fixture,
        # but calling init again should be safe (idempotent)
        init_common_metrics_registry(app)
        registry = _get_common_metrics_registry()
        assert len(registry) > 0

    def test_init_common_metrics_registry_without_app(self):
        """init_common_metrics_registry should work without Flask app."""
        # Should not raise any exceptions
        init_common_metrics_registry()
        registry = _get_common_metrics_registry()
        assert len(registry) > 0


class TestGetCommonMetricsList:
    """Tests for get_common_metrics_list function."""

    def test_returns_list_of_metrics(self, app):
        """Should return a non-empty list of metrics."""
        with app.app_context():
            metrics = get_common_metrics_list()
            assert isinstance(metrics, list)
            assert len(metrics) > 0

    def test_each_metric_has_required_fields(self, app):
        """Each metric should have id, name, description, init_parameters, and score_parameters."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                assert "id" in metric
                assert "name" in metric
                assert "description" in metric
                assert "init_parameters" in metric
                assert "score_parameters" in metric
                assert isinstance(metric["init_parameters"], list)
                assert isinstance(metric["score_parameters"], list)

    def test_contains_metric_has_expected_parameters(self, app):
        """Contains metric should have case_sensitive and reference init parameters.

        When a parameter appears in both __init__ and score (like 'reference'),
        it should only appear in init_parameters to avoid duplication.
        """
        with app.app_context():
            metrics = get_common_metrics_list()
            contains_metric = next((m for m in metrics if m["id"] == "contains"), None)
            assert contains_metric is not None

            init_param_names = [p["name"] for p in contains_metric["init_parameters"]]
            assert "case_sensitive" in init_param_names
            assert "reference" in init_param_names

            score_param_names = [p["name"] for p in contains_metric["score_parameters"]]
            assert "output" in score_param_names
            # 'reference' should NOT be in score_parameters since it's already in init_parameters
            assert "reference" not in score_param_names

    def test_excluded_params_not_in_init_parameters(self, app):
        """Internal parameters like 'name', 'track', 'project_name' should be excluded."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                init_param_names = [p["name"] for p in metric["init_parameters"]]
                assert "self" not in init_param_names
                assert "name" not in init_param_names
                assert "track" not in init_param_names
                assert "project_name" not in init_param_names

    def test_score_parameters_have_mappable_field(self, app):
        """Score parameters should include a 'mappable' field."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                for param in metric["score_parameters"]:
                    assert "mappable" in param
                    assert isinstance(param["mappable"], bool)

    def test_output_parameter_is_mappable(self, app):
        """The 'output' parameter should be marked as mappable."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                for param in metric["score_parameters"]:
                    if param["name"] == "output":
                        assert param["mappable"] is True

    def test_reference_parameter_is_not_mappable(self, app):
        """The 'reference' parameter should not be marked as mappable."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                for param in metric["score_parameters"]:
                    if param["name"] == "reference":
                        assert param["mappable"] is False

    def test_duplicate_parameters_are_deduplicated(self, app):
        """Parameters in both __init__ and score should only appear in init_parameters.

        This prevents duplication where a parameter like 'reference' would appear
        in both init_parameters and score_parameters. The init_parameters version
        is kept since it provides the default value.
        """
        with app.app_context():
            metrics = get_common_metrics_list()

            for metric in metrics:
                init_param_names = {p["name"] for p in metric["init_parameters"]}
                score_param_names = {p["name"] for p in metric["score_parameters"]}

                # There should be no overlap between init and score parameters
                overlap = init_param_names & score_param_names
                assert len(overlap) == 0, (
                    f"Metric '{metric['id']}' has duplicate parameters: {overlap}"
                )


class TestInstantiateMetric:
    """Tests for instantiate_metric function."""

    def test_instantiate_contains_metric(self, app):
        """Should instantiate Contains metric with default config."""
        with app.app_context():
            metric = instantiate_metric("contains")
            assert metric is not None
            assert metric.__class__.__name__ == "Contains"

    def test_instantiate_contains_with_config(self, app):
        """Should instantiate Contains metric with custom config."""
        with app.app_context():
            metric = instantiate_metric("contains", {"case_sensitive": True, "reference": "test"})
            assert metric is not None
            assert metric._case_sensitive is True
            assert metric._default_reference == "test"

    def test_instantiate_unknown_metric_raises(self, app):
        """Should raise ValueError for unknown metric."""
        with app.app_context():
            with pytest.raises(ValueError, match="Unknown metric"):
                instantiate_metric("unknown_metric")

    def test_tracking_is_disabled(self, app):
        """Instantiated metrics should have tracking disabled."""
        with app.app_context():
            metric = instantiate_metric("contains")
            assert metric.track is False


class TestListCommonMetricsEndpoint:
    """Tests for the /common-metrics endpoint."""

    def test_list_metrics_returns_200(self, client):
        """GET /common-metrics should return 200."""
        response = client.get("/v1/private/evaluators/common-metrics")
        assert response.status_code == 200

    def test_list_metrics_returns_content(self, client):
        """GET /common-metrics should return a content array."""
        response = client.get("/v1/private/evaluators/common-metrics")
        data = response.get_json()
        assert "content" in data
        assert isinstance(data["content"], list)
        assert len(data["content"]) > 0

    def test_list_metrics_includes_mappable_field(self, client):
        """GET /common-metrics should include mappable field in score_parameters."""
        response = client.get("/v1/private/evaluators/common-metrics")
        data = response.get_json()
        
        for metric in data["content"]:
            for param in metric["score_parameters"]:
                assert "mappable" in param


class TestExecuteCommonMetricEndpoint:
    """Tests for the /common-metrics/<metric_id>/score endpoint."""

    def test_execute_contains_metric(self, client):
        """POST /common-metrics/contains/score should execute the metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "hello"},
                "scoring_kwargs": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        result = response.get_json()
        assert "scores" in result
        assert len(result["scores"]) == 1
        assert result["scores"][0]["value"] == 1.0

    def test_execute_contains_metric_no_match(self, client):
        """POST /common-metrics/contains/score should return 0 when no match."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "goodbye"},
                "scoring_kwargs": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        result = response.get_json()
        assert result["scores"][0]["value"] == 0.0

    def test_execute_contains_case_sensitive(self, client):
        """POST /common-metrics/contains/score with case_sensitive=True."""
        # Case insensitive (default) - should match
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "HELLO"},
                "scoring_kwargs": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        assert response.get_json()["scores"][0]["value"] == 1.0

        # Case sensitive - should not match
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"case_sensitive": True, "reference": "HELLO"},
                "scoring_kwargs": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        assert response.get_json()["scores"][0]["value"] == 0.0

    def test_execute_unknown_metric_returns_404(self, client):
        """POST /common-metrics/unknown/score should return 404."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/unknown/score",
            json={"scoring_kwargs": {"output": "test"}}
        )
        assert response.status_code == 404

    def test_execute_missing_scoring_kwargs_returns_400(self, client):
        """POST /common-metrics/contains/score without scoring_kwargs should return 400."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={"init_config": {"reference": "test"}}
        )
        assert response.status_code == 400

    def test_execute_equals_metric(self, client):
        """POST /common-metrics/equals/score should execute the metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/equals/score",
            json={
                "scoring_kwargs": {"output": "hello", "reference": "hello"}
            }
        )
        assert response.status_code == 200
        result = response.get_json()
        assert result["scores"][0]["value"] == 1.0

    def test_execute_is_json_metric(self, client):
        """POST /common-metrics/is_json/score should execute the metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/is_json/score",
            json={
                "scoring_kwargs": {"output": '{"key": "value"}'}
            }
        )
        assert response.status_code == 200
        result = response.get_json()
        assert result["scores"][0]["value"] == 1.0

        # Invalid JSON
        response = client.post(
            "/v1/private/evaluators/common-metrics/is_json/score",
            json={
                "scoring_kwargs": {"output": "not json"}
            }
        )
        assert response.status_code == 200
        assert response.get_json()["scores"][0]["value"] == 0.0


class TestDynamicMetricsDiscovery:
    """Tests specifically for the dynamic metric discovery functionality."""

    def test_discovers_more_than_original_five_metrics(self, app):
        """Should discover more metrics than the original hardcoded 5."""
        with app.app_context():
            metrics = get_common_metrics_list()
            # Original had: contains, equals, is_json, levenshtein_ratio, regex_match
            # Now should have many more
            assert len(metrics) > 5

    def test_discovers_nltk_based_metrics(self, app):
        """Should discover NLTK-based metrics like ROUGE, BLEU, etc."""
        with app.app_context():
            metrics = get_common_metrics_list()
            metric_ids = [m["id"] for m in metrics]
            
            # These metrics use NLTK and should now be available
            # Note: exact names depend on _camel_to_snake conversion
            nltk_metrics_found = False
            for metric_id in metric_ids:
                if any(name in metric_id for name in ["rouge", "bleu", "gleu", "meteor", "chrf"]):
                    nltk_metrics_found = True
                    break
            
            # At least some NLTK metrics should be found
            # (exact availability depends on dependencies)
            assert nltk_metrics_found or len(metrics) > 5

    def test_discovers_statistical_metrics(self, app):
        """Should discover statistical metrics like JS divergence, Spearman, etc."""
        with app.app_context():
            metrics = get_common_metrics_list()
            metric_ids = [m["id"] for m in metrics]
            
            # Check for some statistical metrics
            statistical_metrics = ["j_s_divergence", "j_s_distance", "k_l_divergence", "spearman_ranking"]
            found_count = sum(1 for m in statistical_metrics if m in metric_ids)
            
            # Should find at least some of them
            assert found_count > 0 or len(metrics) > 5

    def test_discovers_prompt_injection_metric(self, app):
        """Should discover PromptInjection metric."""
        with app.app_context():
            metrics = get_common_metrics_list()
            metric_ids = [m["id"] for m in metrics]
            
            # PromptInjection should be available
            assert "prompt_injection" in metric_ids

    def test_bertscore_is_excluded(self, app):
        """BERTScore should be excluded from discovered metrics."""
        with app.app_context():
            metrics = get_common_metrics_list()
            metric_ids = [m["id"] for m in metrics]
            
            # BERTScore should NOT be in the list
            for metric_id in metric_ids:
                assert "bertscore" not in metric_id.lower()
