import pytest


def test_check_results_requires_run_id_in_non_interactive_mode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    pytest.importorskip("modal")
    from benchmarks import check_results

    monkeypatch.setattr(check_results.sys.stdin, "isatty", lambda: False, raising=False)

    with pytest.raises(SystemExit) as exc:
        check_results.main(
            run_id=None,
            list_runs=False,
            watch=False,
            detailed=False,
            raw=False,
            show_errors=False,
            task=None,
            watch_interval=1,
        )
    assert exc.value.code == 1
