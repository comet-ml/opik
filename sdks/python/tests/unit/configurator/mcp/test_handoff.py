import subprocess

import pytest

from opik.configurator.mcp import handoff


class TestFirstTracedProject:
    """Which project the closing prompt points the agent at."""

    def test_no_projects__is_no_project(self):
        assert handoff._first_traced_project([]) is None

    def test_a_project_without_traces__does_not_count(self):
        """`last_updated_trace_at` stays null until the first trace arrives."""
        projects = [{"name": "my-app", "last_updated_trace_at": None}]

        assert handoff._first_traced_project(projects) is None

    def test_demo_projects__do_not_count(self):
        """Every workspace starts with demos, and they are nobody's own app."""
        projects = [
            {"name": name, "last_updated_trace_at": "2026-09-30T10:00:00Z"}
            for name in handoff.DEMO_PROJECT_NAMES
        ]

        assert handoff._first_traced_project(projects) is None

    def test_the_users_own_traced_project__is_chosen(self):
        projects = [
            {
                "name": "Demo evaluation",
                "last_updated_trace_at": "2026-09-30T10:00:00Z",
            },
            {"name": "my-app", "last_updated_trace_at": "2026-09-29T10:00:00Z"},
        ]

        assert handoff._first_traced_project(projects) == "my-app"

    def test_several__takes_the_most_recently_traced(self):
        """The one they are working on now, not the one they abandoned."""
        projects = [
            {"name": "old", "last_updated_trace_at": "2026-01-01T10:00:00Z"},
            {"name": "current", "last_updated_trace_at": "2026-09-30T10:00:00Z"},
        ]

        assert handoff._first_traced_project(projects) == "current"

    def test_junk_in_the_listing__is_survived(self):
        """A project payload is the deployment's to shape; this only reads it."""
        projects = ["not a dict", {}, {"last_updated_trace_at": "2026-09-30T10:00:00Z"}]

        assert handoff._first_traced_project(projects) is None


class TestClosingPrompt:
    def test_no_traces__points_at_the_instrument_skill(self):
        prompt = handoff.closing_prompt(None)

        assert "/opik-instrument" in prompt

    def test_traces__points_at_the_diagnose_skill_and_names_the_project(self):
        prompt = handoff.closing_prompt("my-app")

        assert "/opik-diagnose" in prompt
        assert "my-app" in prompt

    def test_traces__asks_what_is_there__not_for_a_fix(self):
        """The first question looks at the data rather than asking for a fix."""
        prompt = handoff.closing_prompt("my-app")

        assert "overview" in prompt
        assert "fix" not in prompt


class TestTracedProject:
    def test_no_listing__answers_none(self, monkeypatch):
        monkeypatch.setattr(
            handoff.opik_rest_helpers, "list_projects", lambda **kw: None
        )

        assert handoff.traced_project(None, None, "https://opik/api/") is None

    def test_a_listing__names_the_traced_project(self, monkeypatch):
        listing = [{"name": "my-app", "last_updated_trace_at": "2026-09-30T10:00:00Z"}]
        monkeypatch.setattr(
            handoff.opik_rest_helpers, "list_projects", lambda **kw: listing
        )

        assert handoff.traced_project(None, None, "https://opik/api/") == "my-app"


class TestLaunching:
    def test_a_gui_client__has_no_launch_command(self):
        """Nothing to hand a prompt to; the caller shows it instead."""
        assert handoff.launch_command("cursor") is None
        assert handoff.launch_command("vscode") is None

    def test_a_terminal_agent__is_started_from_where_it_is_installed(self, monkeypatch):
        monkeypatch.setattr(handoff.shutil, "which", lambda name: "/usr/bin/claude")

        assert handoff.launch_command("claude-code") == ["/usr/bin/claude"]

    def test_a_terminal_agent_that_is_not_installed__has_none(self, monkeypatch):
        monkeypatch.setattr(handoff.shutil, "which", lambda name: None)

        assert handoff.launch_command("claude-code") is None

    def test_launching__execs_the_agent_with_the_prompt(self, monkeypatch):
        """The user agreed to this exact question a line ago, so it is sent."""
        recorded = {}
        monkeypatch.setattr(handoff.sys, "platform", "darwin")
        monkeypatch.setattr(
            handoff.os,
            "execvp",
            lambda executable, argv: recorded.update(executable=executable, argv=argv),
        )

        handoff.launch(["/usr/bin/claude"], "look at my traces")

        assert recorded == {
            "executable": "/usr/bin/claude",
            "argv": ["/usr/bin/claude", "look at my traces"],
        }

    def test_launching_on_windows__runs_the_agent_and_exits_with_its_status(
        self, monkeypatch
    ):
        """Windows has no exec: `os.execvp` there returns the console to the shell
        while the agent still reads from it, and passes the prompt unquoted, so it
        arrives split into words. The agent runs as a child instead."""
        recorded = {}
        monkeypatch.setattr(handoff.sys, "platform", "win32")
        monkeypatch.setattr(
            handoff.os, "execvp", lambda *args: pytest.fail("Windows must not exec")
        )
        monkeypatch.setattr(handoff.signal, "signal", lambda *args: None)

        def fake_run(argv):
            recorded["argv"] = argv
            return subprocess.CompletedProcess(argv, 3)

        monkeypatch.setattr(handoff.subprocess, "run", fake_run)

        with pytest.raises(SystemExit) as exited:
            handoff.launch(["C:\\bin\\claude.CMD"], "look at my traces")

        assert recorded["argv"] == ["C:\\bin\\claude.CMD", "look at my traces"]
        assert exited.value.code == 3
