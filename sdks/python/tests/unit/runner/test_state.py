from opik.runner import constants, state


BASE_URL = "https://api.opik.test"


class TestRunnerState:
    def test_save_and_load__valid_state__round_trips(self):
        runner_state = state.RunnerState(
            runner_id="r-123", pid=42, name="my-runner", base_url=BASE_URL
        )
        state.save_runner_state(runner_state)
        loaded = state.load_runner_state()

        assert loaded is not None
        assert loaded.runner_id == "r-123"
        assert loaded.pid == 42
        assert loaded.name == "my-runner"

    def test_load__no_state_file__returns_none(self):
        assert state.load_runner_state() is None

    def test_load__corrupted_json__returns_none(self):
        with open(constants.runner_state_file(), "w") as f:
            f.write("{bad json")
        assert state.load_runner_state() is None

    def test_clear__existing_state__removes_file(self):
        runner_state = state.RunnerState(
            runner_id="r-123", pid=42, name="r", base_url=BASE_URL
        )
        state.save_runner_state(runner_state)
        state.clear_runner_state()
        assert state.load_runner_state() is None

    def test_clear__no_state_file__noop(self):
        state.clear_runner_state()
