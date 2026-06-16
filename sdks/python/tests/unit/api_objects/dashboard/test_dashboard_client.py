import copy

import pytest
from unittest.mock import patch

from opik import exceptions
from opik.api_objects import opik_client
from opik.api_objects.dashboard import types
from opik.api_objects.dashboard.dashboard import Dashboard
from opik.rest_api.types import dashboard_public as dp


class FakeDashboardsApi:
    def __init__(self, store):
        self._store = store
        self.update_calls = []
        self.deleted = False

    def update_dashboard(
        self, dashboard_id, *, name=None, type=None, description=None, config=None
    ):
        self.update_calls.append(
            {"name": name, "type": type, "description": description, "config": config}
        )
        if config is not None:
            self._store["config"] = config
        if name is not None:
            self._store["name"] = name
        if description is not None:
            self._store["description"] = description
        return self._make()

    def get_dashboard_by_id(self, dashboard_id):
        return self._make()

    def delete_dashboard(self, dashboard_id):
        self.deleted = True

    def _make(self):
        return dp.DashboardPublic(
            id=self._store["id"],
            name=self._store["name"],
            type=self._store.get("type"),
            description=self._store.get("description"),
            config=self._store["config"],
        )


class FakeRest:
    def __init__(self, store):
        self.dashboards = FakeDashboardsApi(store)


def _make_dashboard(config, dashboard_type="multi_project"):
    store = {"id": "d1", "name": "My dash", "type": dashboard_type, "config": config}
    rest = FakeRest(store)
    public = dp.DashboardPublic(
        id="d1", name="My dash", type=dashboard_type, config=copy.deepcopy(config)
    )
    return Dashboard(dashboard_public=public, rest_client=rest), rest, store


def _config_with_legacy_widget():
    return {
        "version": 4,
        "mysteryTopLevel": "keep-me",
        "sections": [
            {
                "id": "s1",
                "title": "Overview",
                "widgets": [
                    {
                        "id": "old",
                        "type": "future_widget",
                        "config": {"unknownField": 123},
                        "futureWidgetProp": "x",
                    }
                ],
                "layout": [{"i": "old", "x": 0, "y": 0, "w": 2, "h": 2}],
            }
        ],
        "lastModified": 111,
    }


def test_add_widget__preserves_unknown_fields_round_trip():
    dashboard, rest, _ = _make_dashboard(_config_with_legacy_widget())

    widget_id = dashboard.add_widget(
        "s1",
        types.DashboardWidget(
            type=types.WidgetType.TEXT_MARKDOWN,
            title="Notes",
            config=types.TextMarkdownConfig(content="hello"),
        ),
    )

    config = dashboard.config
    assert config["mysteryTopLevel"] == "keep-me"
    old = next(w for w in config["sections"][0]["widgets"] if w["id"] == "old")
    assert old["futureWidgetProp"] == "x"
    assert old["config"]["unknownField"] == 123
    assert widget_id in [w["id"] for w in config["sections"][0]["widgets"]]
    assert widget_id in [i["i"] for i in config["sections"][0]["layout"]]
    assert config["version"] == 4
    assert config["lastModified"] != 111


def test_add_widget__incompatible_type_raises_before_write():
    config = {
        "version": 4,
        "sections": [{"id": "s1", "title": "t", "widgets": [], "layout": []}],
        "lastModified": 1,
    }
    dashboard, rest, _ = _make_dashboard(config, dashboard_type="multi_project")

    with pytest.raises(exceptions.DashboardValidationError, match="not supported"):
        dashboard.add_widget(
            "s1", types.DashboardWidget(type=types.WidgetType.EXPERIMENT_LEADERBOARD)
        )

    assert rest.dashboards.update_calls == []


def test_add_widget__unknown_section_raises():
    config = {
        "version": 4,
        "sections": [{"id": "s1", "title": "t", "widgets": [], "layout": []}],
        "lastModified": 1,
    }
    dashboard, _, _ = _make_dashboard(config)

    with pytest.raises(exceptions.DashboardValidationError, match="not found"):
        dashboard.add_widget(
            "missing", types.DashboardWidget(type=types.WidgetType.TEXT_MARKDOWN)
        )


def test_mutation__unknown_version_refused():
    config = {
        "version": 5,
        "sections": [{"id": "s1", "title": "t", "widgets": [], "layout": []}],
        "lastModified": 1,
    }
    dashboard, rest, _ = _make_dashboard(config)

    with pytest.raises(exceptions.DashboardValidationError, match="schema version 5"):
        dashboard.add_widget(
            "s1", types.DashboardWidget(type=types.WidgetType.TEXT_MARKDOWN)
        )
    assert rest.dashboards.update_calls == []


def test_reads_never_fail__on_unknown_widget_type():
    dashboard, _, _ = _make_dashboard(_config_with_legacy_widget())

    state = dashboard.state
    assert len(state.sections) == 1
    assert state.sections[0].widgets[0].type == "future_widget"


def test_update_widget__merges_config():
    config = {
        "version": 4,
        "sections": [
            {
                "id": "s1",
                "title": "t",
                "widgets": [
                    {"id": "w1", "type": "text_markdown", "config": {"content": "a"}}
                ],
                "layout": [{"i": "w1", "x": 0, "y": 0, "w": 2, "h": 4}],
            }
        ],
        "lastModified": 1,
    }
    dashboard, _, _ = _make_dashboard(config)

    dashboard.update_widget("w1", title="New", config={"content": "b"})

    widget = dashboard.config["sections"][0]["widgets"][0]
    assert widget["title"] == "New"
    assert widget["config"]["content"] == "b"


def test_remove_widget__removes_widget_and_layout():
    config = {
        "version": 4,
        "sections": [
            {
                "id": "s1",
                "title": "t",
                "widgets": [
                    {"id": "w1", "type": "text_markdown", "config": {}},
                    {"id": "w2", "type": "text_markdown", "config": {}},
                ],
                "layout": [
                    {"i": "w1", "x": 0, "y": 0, "w": 2, "h": 4},
                    {"i": "w2", "x": 2, "y": 0, "w": 2, "h": 4},
                ],
            }
        ],
        "lastModified": 1,
    }
    dashboard, _, _ = _make_dashboard(config)

    dashboard.remove_widget("w1")

    section = dashboard.config["sections"][0]
    assert [w["id"] for w in section["widgets"]] == ["w2"]
    assert [i["i"] for i in section["layout"]] == ["w2"]


def test_remove_widget__missing_raises():
    config = {
        "version": 4,
        "sections": [{"id": "s1", "title": "t", "widgets": [], "layout": []}],
        "lastModified": 1,
    }
    dashboard, _, _ = _make_dashboard(config)
    with pytest.raises(exceptions.DashboardValidationError, match="not found"):
        dashboard.remove_widget("ghost")


def test_rename__sends_name_only():
    dashboard, rest, _ = _make_dashboard(_config_with_legacy_widget())
    dashboard.rename("Renamed")
    assert dashboard.name == "Renamed"
    assert rest.dashboards.update_calls[-1]["name"] == "Renamed"
    assert rest.dashboards.update_calls[-1]["config"] is None


def test_set_description__sends_description_only():
    dashboard, rest, _ = _make_dashboard(_config_with_legacy_widget())
    dashboard.set_description("desc")
    assert dashboard.description == "desc"
    assert rest.dashboards.update_calls[-1]["description"] == "desc"
    assert rest.dashboards.update_calls[-1]["config"] is None


def test_add_section__appends_and_returns_id():
    config = {
        "version": 4,
        "sections": [{"id": "s1", "title": "t", "widgets": [], "layout": []}],
        "lastModified": 1,
    }
    dashboard, _, _ = _make_dashboard(config)
    new_id = dashboard.add_section("Second")
    section_ids = [s["id"] for s in dashboard.config["sections"]]
    assert new_id in section_ids
    assert len(section_ids) == 2


def test_delete__calls_rest():
    dashboard, rest, _ = _make_dashboard(_config_with_legacy_widget())
    dashboard.delete()
    assert rest.dashboards.deleted is True


# --- Opik client-level methods ---


def test_create_dashboard__default_section_and_config():
    client = opik_client.Opik()
    captured = {}

    def fake_create(*, name, config, type, description, project_id, project_name):
        captured.update(
            name=name,
            config=config,
            type=type,
            description=description,
            project_id=project_id,
            project_name=project_name,
        )
        return dp.DashboardPublic(id="d1", name=name, type=type, config=config)

    with patch.object(
        client._rest_client.dashboards, "create_dashboard", side_effect=fake_create
    ):
        dashboard = client.create_dashboard(
            name="Prod", type=types.DashboardType.MULTI_PROJECT
        )

    assert dashboard.name == "Prod"
    assert captured["type"] == "multi_project"
    assert captured["config"]["version"] == types.DASHBOARD_VERSION
    assert len(captured["config"]["sections"]) == 1
    assert captured["config"]["sections"][0]["title"] == "Overview"
    assert "lastModified" in captured["config"]


def test_create_dashboard__with_provided_sections():
    client = opik_client.Opik()
    captured = {}

    def fake_create(*, name, config, type, description, project_id, project_name):
        captured["config"] = config
        return dp.DashboardPublic(id="d1", name=name, type=type, config=config)

    section = types.DashboardSection(
        title="Custom",
        widgets=[types.DashboardWidget(id="w1", type=types.WidgetType.TEXT_MARKDOWN)],
        layout=[types.DashboardLayoutItem(id="w1", x=0, y=0, w=2, h=4)],
    )

    with patch.object(
        client._rest_client.dashboards, "create_dashboard", side_effect=fake_create
    ):
        client.create_dashboard(name="Prod", sections=[section])

    assert captured["config"]["sections"][0]["title"] == "Custom"
    assert captured["config"]["sections"][0]["widgets"][0]["id"] == "w1"


def test_create_dashboard__incompatible_widget_raises_before_create():
    client = opik_client.Opik()

    section = types.DashboardSection(
        title="Custom",
        widgets=[
            types.DashboardWidget(id="w1", type=types.WidgetType.EXPERIMENT_LEADERBOARD)
        ],
        layout=[types.DashboardLayoutItem(id="w1", x=0, y=0, w=6, h=6)],
    )

    with patch.object(
        client._rest_client.dashboards, "create_dashboard"
    ) as mock_create:
        with pytest.raises(exceptions.DashboardValidationError, match="not supported"):
            client.create_dashboard(
                name="Prod",
                type=types.DashboardType.MULTI_PROJECT,
                sections=[section],
            )

    mock_create.assert_not_called()


def test_replace_sections__incompatible_widget_raises_before_write():
    config = {
        "version": 4,
        "sections": [{"id": "s1", "title": "t", "widgets": [], "layout": []}],
        "lastModified": 1,
    }
    dashboard, rest, _ = _make_dashboard(config, dashboard_type="multi_project")

    section = types.DashboardSection(
        title="Custom",
        widgets=[
            types.DashboardWidget(id="w1", type=types.WidgetType.EXPERIMENT_LEADERBOARD)
        ],
        layout=[types.DashboardLayoutItem(id="w1", x=0, y=0, w=6, h=6)],
    )

    with pytest.raises(exceptions.DashboardValidationError, match="not supported"):
        dashboard.replace_sections([section])

    assert rest.dashboards.update_calls == []


def test_get_dashboard__returns_wrapper():
    client = opik_client.Opik()
    public = dp.DashboardPublic(
        id="d1",
        name="My dash",
        type="multi_project",
        config={"version": 4, "sections": []},
    )
    with patch.object(
        client._rest_client.dashboards, "get_dashboard_by_id", return_value=public
    ):
        dashboard = client.get_dashboard("d1")
    assert dashboard.id == "d1"
    assert dashboard.name == "My dash"


def test_delete_dashboard__calls_rest():
    client = opik_client.Opik()
    with patch.object(
        client._rest_client.dashboards, "delete_dashboard"
    ) as mock_delete:
        client.delete_dashboard("d1")
        mock_delete.assert_called_once_with("d1")


def test_find_dashboards__paginates_and_respects_max_results():
    from opik.api_objects.dashboard import rest_operations
    from opik.rest_api.types import dashboard_page_public as dpp

    pages = {
        1: [
            dp.DashboardPublic(
                id=f"d{i}", name=f"d{i}", config={"version": 4, "sections": []}
            )
            for i in range(100)
        ],
        2: [
            dp.DashboardPublic(
                id="d100", name="d100", config={"version": 4, "sections": []}
            )
        ],
    }

    class PaginatedApi:
        def __init__(self):
            self.calls = []

        def find_dashboards(self, *, page, size, name, project_id, sorting, filters):
            self.calls.append(page)
            return dpp.DashboardPagePublic(content=pages.get(page, []))

    class Rest:
        def __init__(self):
            self.dashboards = PaginatedApi()

    rest = Rest()
    result = rest_operations.find_dashboards(rest_client=rest, max_results=100)
    assert len(result) == 100
    # only the first page is needed to satisfy max_results
    assert rest.dashboards.calls == [1]
    assert all(isinstance(d, Dashboard) for d in result)
