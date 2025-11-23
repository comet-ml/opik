from benchmarks.core import benchmark_config
from benchmarks.utils.task_runner import _resolve_metrics


def test_resolve_metrics_from_strings() -> None:
    dataset_cfg = benchmark_config.BenchmarkDatasetConfig(
        name="tiny_test",
        display_name="Tiny",
        metrics=[lambda _item, _out: 0.1],
    )

    metrics = _resolve_metrics(
        dataset_cfg,
        ["benchmarks.metrics.hotpot.hotpot_f1"],
    )
    assert len(metrics) == 1
    assert callable(metrics[0])


def test_resolve_metrics_from_objects_with_kwargs() -> None:
    dataset_cfg = benchmark_config.BenchmarkDatasetConfig(
        name="tiny_test",
        display_name="Tiny",
        metrics=[lambda _item, _out: 0.1],
    )

    metrics = _resolve_metrics(
        dataset_cfg,
        [
            {
                "path": "benchmarks.metrics.hotpot.hotpot_f1",
            }
        ],
    )
    assert len(metrics) == 1
    assert callable(metrics[0])


def test_resolve_metrics_defaults_when_none() -> None:
    fn = lambda _item, _out: 0.2  # noqa: E731
    dataset_cfg = benchmark_config.BenchmarkDatasetConfig(
        name="tiny_test",
        display_name="Tiny",
        metrics=[fn],
    )
    metrics = _resolve_metrics(dataset_cfg, None)
    assert metrics == [fn]
