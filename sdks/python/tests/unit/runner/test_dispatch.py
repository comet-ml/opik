import json
import io


from opik.runner import dispatch


class TestShouldDispatch:
    def test_should_dispatch__agent_matches__returns_true(self, monkeypatch):
        monkeypatch.setenv("OPIK_AGENT", "my_agent")
        assert dispatch.should_dispatch("my_agent") is True

    def test_should_dispatch__agent_differs__returns_false(self, monkeypatch):
        monkeypatch.setenv("OPIK_AGENT", "other_agent")
        assert dispatch.should_dispatch("my_agent") is False

    def test_should_dispatch__agent_not_set__returns_false(self, monkeypatch):
        monkeypatch.delenv("OPIK_AGENT", raising=False)
        assert dispatch.should_dispatch("my_agent") is False


class TestRunDispatch:
    def test_run_dispatch__valid_inputs__calls_func(self, monkeypatch, tmp_path):
        result_file = str(tmp_path / "result.json")
        monkeypatch.setenv("OPIK_RESULT_FILE", result_file)
        monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"x": 1, "y": 2})))

        def add(x, y):
            return x + y

        result = dispatch.run_dispatch(add)
        assert result == 3

        with open(result_file) as f:
            data = json.load(f)
        assert data["result"] == 3

    def test_run_dispatch__no_result_file__succeeds(self, monkeypatch):
        monkeypatch.delenv("OPIK_RESULT_FILE", raising=False)
        monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"msg": "hello"})))

        def echo(msg):
            return msg

        result = dispatch.run_dispatch(echo)
        assert result == "hello"

    def test_run_dispatch__trace_id__injects_headers(self, monkeypatch, tmp_path):
        result_file = str(tmp_path / "result.json")
        monkeypatch.setenv("OPIK_RESULT_FILE", result_file)
        monkeypatch.setenv("OPIK_TRACE_ID", "trace-abc-123")
        monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"x": 1})))

        captured = {}

        def func(x, **kwargs):
            captured["headers"] = kwargs.get("opik_distributed_trace_headers")
            return x

        dispatch.run_dispatch(func)

        assert captured["headers"] is not None
        assert captured["headers"]["opik_trace_id"] == "trace-abc-123"
        assert "opik_parent_span_id" in captured["headers"]

    def test_run_dispatch__no_trace_id__no_headers(self, monkeypatch, tmp_path):
        result_file = str(tmp_path / "result.json")
        monkeypatch.setenv("OPIK_RESULT_FILE", result_file)
        monkeypatch.delenv("OPIK_TRACE_ID", raising=False)
        monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"x": 1})))

        captured = {}

        def func(x, **kwargs):
            captured["x"] = x
            captured["kwargs"] = kwargs
            return x

        dispatch.run_dispatch(func)
        assert captured["x"] == 1
        assert "opik_distributed_trace_headers" not in captured["kwargs"]
