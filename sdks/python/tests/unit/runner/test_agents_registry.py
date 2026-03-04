import json

from opik.runner import agents_registry, constants


def _make_agent(name="my_agent", executable="/usr/bin/python"):
    return {
        "name": name,
        "executable": executable,
        "source_file": "/path/to/file.py",
        "description": "A test agent",
        "language": "python",
        "params": [{"name": "query", "type": "str"}],
    }


class TestRegisterAgent:
    def test_register__new_agent__creates_file(self):
        agent = _make_agent()
        agents_registry.register_agent(agent)

        with open(constants.agents_file()) as f:
            data = json.load(f)
        assert len(data["agents"]) == 1
        assert data["agents"][0]["name"] == "my_agent"

    def test_register__existing_agent__upserts(self):
        agents_registry.register_agent(_make_agent(executable="/old"))
        agents_registry.register_agent(_make_agent(executable="/new"))

        agents = agents_registry.load_agents()
        assert len(agents) == 1
        assert agents["my_agent"]["executable"] == "/new"

    def test_register__two_agents__preserves_both(self):
        agents_registry.register_agent(_make_agent("agent_a"))
        agents_registry.register_agent(_make_agent("agent_b"))

        agents = agents_registry.load_agents()
        assert "agent_a" in agents
        assert "agent_b" in agents


class TestLoadAgents:
    def test_load__multiple__returns_all(self):
        agents_registry.register_agent(_make_agent("a"))
        agents_registry.register_agent(_make_agent("b"))

        agents = agents_registry.load_agents()
        assert set(agents.keys()) == {"a", "b"}

    def test_load__no_file__returns_empty(self):
        agents = agents_registry.load_agents()
        assert agents == {}

    def test_load__corrupted_json__returns_empty(self):
        with open(constants.agents_file(), "w") as f:
            f.write("{invalid json")

        agents = agents_registry.load_agents()
        assert agents == {}

    def test_load__os_error_reading_file__returns_empty(self, monkeypatch):
        constants.ensure_opik_home()
        agents_path = constants.agents_file()
        with open(agents_path, "w") as f:
            json.dump({"agents": [{"name": "a"}]}, f)

        monkeypatch.setattr(
            "builtins.open",
            lambda *a, **kw: (_ for _ in ()).throw(OSError("disk error")),
        )

        agents = agents_registry.load_agents()
        assert agents == {}
