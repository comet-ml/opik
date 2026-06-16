import copy
import logging
from contextlib import contextmanager
from typing import Any, Dict, Generator, List, Optional, Union

from opik import exceptions, id_helpers
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import dashboard_public as rest_dashboard_public

from . import layout, types, validation

LOGGER = logging.getLogger(__name__)


class Dashboard:
    """A high-level wrapper around an Opik dashboard.

    Do not instantiate directly; use :meth:`opik.Opik.create_dashboard`,
    :meth:`opik.Opik.get_dashboard`, or :meth:`opik.Opik.get_dashboards`.

    The wrapper holds the raw ``config`` blob read from the backend as its source
    of truth. Mutators edit that blob in place and PATCH the whole document back
    (the backend replaces the column wholesale), so fields the SDK does not model
    are preserved. Mutations are last-writer-wins: there is no optimistic
    concurrency control, so concurrent edits to the same dashboard can clobber
    each other. Call :meth:`reload` to re-sync before mutating if needed.
    """

    def __init__(
        self,
        dashboard_public: rest_dashboard_public.DashboardPublic,
        rest_client: rest_api_client.OpikApi,
        client: Optional[Any] = None,
    ) -> None:
        self._rest_client = rest_client
        self.client = client
        self._absorb(dashboard_public)

    @classmethod
    def from_public(
        cls,
        dashboard_public: rest_dashboard_public.DashboardPublic,
        rest_client: rest_api_client.OpikApi,
        client: Optional[Any] = None,
    ) -> "Dashboard":
        return cls(
            dashboard_public=dashboard_public, rest_client=rest_client, client=client
        )

    def _absorb(self, dashboard_public: rest_dashboard_public.DashboardPublic) -> None:
        if dashboard_public.id is None:
            raise exceptions.DashboardValidationError(
                "Backend returned a dashboard without an id"
            )
        self._id: str = dashboard_public.id
        self._name = dashboard_public.name
        self._description = dashboard_public.description
        self._type = dashboard_public.type
        self._scope = dashboard_public.scope
        self._project_id: Optional[str] = dashboard_public.project_id
        self._config: Dict[str, Any] = copy.deepcopy(dashboard_public.config or {})
        self._created_at = dashboard_public.created_at
        self._last_updated_at = dashboard_public.last_updated_at

    @property
    def id(self) -> str:
        """Unique identifier of the dashboard."""
        return self._id

    @property
    def name(self) -> str:
        """Display name shown in the Opik UI."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """Optional free-text description of the dashboard's purpose."""
        return self._description

    @property
    def type(self) -> Optional[str]:
        """Dashboard type: ``"multi_project"`` for project-metric charts,
        or ``"experiments"`` for experiment evaluation charts.

        The type constrains which widget types are allowed (see
        :class:`~opik.api_objects.dashboard.types.WidgetType`).
        """
        return self._type

    @property
    def project_id(self) -> Optional[str]:
        """Project this dashboard is scoped to, or ``None`` for workspace-level dashboards.

        Project-scoped widget types (``project_metrics``, ``project_stats_card``) can
        only be added to dashboards that have a project. Pass ``project_name`` or
        ``project_id`` to :meth:`opik.Opik.create_dashboard` to associate a project.
        """
        return self._project_id

    @property
    def scope(self) -> Optional[str]:
        """Visibility scope set by the backend: ``"workspace"`` (shared with all
        workspace members) or ``"insights"`` (platform-managed insight dashboard).
        """
        return self._scope

    @property
    def config(self) -> Dict[str, Any]:
        """A deep copy of the raw JSON config blob persisted by the backend.

        The schema is owned by the frontend; use :attr:`sections` for a typed view.
        Direct mutation of this copy has no effect — use the mutator methods instead.
        """
        return copy.deepcopy(self._config)

    @property
    def state(self) -> types.DashboardState:
        """The config parsed into a typed :class:`~opik.api_objects.dashboard.types.DashboardState`
        (version, sections, lastModified). Returns a new object on every access.
        """
        return types.DashboardState.model_validate(self._config)

    @property
    def sections(self) -> List[types.DashboardSection]:
        """Typed sections of the dashboard, each containing widgets and their grid layout.
        Returns a new list on every access; mutations do not affect the dashboard — call
        :meth:`replace_sections` to persist changes.
        """
        return self.state.sections

    def add_widget(
        self,
        widget: Union[types.DashboardWidget, Dict[str, Any]],
        *,
        section_id: Optional[str] = None,
        size: Optional[Dict[str, int]] = None,
    ) -> str:
        """Add a widget to a section and auto-place it on the grid.

        The widget is positioned using the same algorithm as the Opik UI, appending
        it to the first available slot that fits its default (or overridden) size.

        Args:
            widget: A :class:`~opik.api_objects.dashboard.types.DashboardWidget`
                instance or a raw config dict.
            section_id: ID of the section to add the widget to. Defaults to the
                first section on the dashboard.
            size: Optional ``{"w": int, "h": int}`` to override the widget's
                default grid dimensions (columns × rows).

        Returns:
            The ID of the newly added widget.
        """
        self._assert_config_writable()
        widget_dict = copy.deepcopy(validation.as_widget_dict(widget))
        if not widget_dict.get("id"):
            widget_dict["id"] = id_helpers.generate_id()
        if size is not None and ("w" not in size or "h" not in size):
            raise exceptions.DashboardValidationError(
                "size must contain both 'w' and 'h' keys"
            )
        validation.validate_widget_for_dashboard(widget_dict, self._type)
        validation.inject_project_id(widget_dict, self._project_id)

        resolved_section_id = (
            self._default_section_id() if section_id is None else section_id
        )
        with self._atomic_config():
            section = self._get_section(resolved_section_id)
            section.setdefault("widgets", []).append(widget_dict)
            typed_layout = [
                types.DashboardLayoutItem.model_validate(i)
                for i in section.get("layout", [])
            ]
            section["layout"] = [
                item.to_jsonable()
                for item in layout.calculate_layout_for_adding_widget(
                    typed_layout,
                    widget_type=str(widget_dict["type"]),
                    widget_id=str(widget_dict["id"]),
                    size=size,
                )
            ]
            self._commit_config()
        return str(widget_dict["id"])

    def remove_widget(self, widget_id: str) -> None:
        """Remove a widget and its grid layout entry from whichever section holds it.

        Raises :class:`~opik.exceptions.DashboardValidationError` if the widget ID
        is not found.
        """
        self._assert_config_writable()
        removed = False
        for section in self._config.get("sections", []):
            widgets = section.get("widgets", [])
            kept = [w for w in widgets if w.get("id") != widget_id]
            if len(kept) != len(widgets):
                removed = True

        if not removed:
            raise exceptions.DashboardValidationError(
                f"Widget with id {widget_id!r} not found in dashboard"
            )

        with self._atomic_config():
            for section in self._config.get("sections", []):
                widgets = section.get("widgets", [])
                kept = [w for w in widgets if w.get("id") != widget_id]
                if len(kept) != len(widgets):
                    section["widgets"] = kept
                    typed_layout = [
                        types.DashboardLayoutItem.model_validate(i)
                        for i in section.get("layout", [])
                    ]
                    section["layout"] = [
                        item.to_jsonable()
                        for item in layout.remove_widget_from_layout(
                            typed_layout, widget_id
                        )
                    ]
            self._commit_config()

    def update_widget(
        self,
        widget_id: str,
        *,
        title: Optional[str] = None,
        subtitle: Optional[str] = None,
        config: Optional[Union[types.WidgetConfig, Dict[str, Any]]] = None,
    ) -> None:
        """Update a widget's display properties or configuration.

        Only the supplied keyword arguments are changed; omitted ones are left
        as-is. ``config`` is *merged* into the widget's existing config dict
        (not replaced), so partial updates are safe.
        """
        self._assert_config_writable()
        with self._atomic_config():
            widget = self._find_widget(widget_id)

            if title is not None:
                widget["title"] = title
            if subtitle is not None:
                widget["subtitle"] = subtitle
            if config is not None:
                config_dict = (
                    config.to_jsonable()
                    if isinstance(config, types._DashboardModel)
                    else config
                )
                if not isinstance(widget.get("config"), dict):
                    widget["config"] = {}
                widget["config"].update(config_dict)

            validation.validate_widget_for_dashboard(widget, self._type)
            self._commit_config()

    def add_section(self, title: str) -> str:
        """Append a new empty section to the dashboard and return its ID.

        Sections group related widgets visually; each dashboard starts with one
        default section created by the backend.
        """
        self._assert_config_writable()
        section = types.DashboardSection(title=title).to_jsonable()
        with self._atomic_config():
            self._config.setdefault("sections", []).append(section)
            self._commit_config()
        return str(section["id"])

    def replace_sections(
        self,
        sections: List[Union[types.DashboardSection, Dict[str, Any]]],
    ) -> None:
        """Replace all sections of the dashboard at once.

        This is the primary way to reorder sections, move widgets between sections,
        or adjust widget grid positions (x, y, w, h) — mutate the list returned by
        :attr:`sections` and pass it back here.
        """
        self._assert_config_writable()
        section_dicts = copy.deepcopy(validation.as_section_dicts(sections))
        for section in section_dicts:
            for widget in section.get("widgets", []):
                validation.validate_widget_for_dashboard(widget, self._type)
                validation.inject_project_id(widget, self._project_id)
        with self._atomic_config():
            self._config["sections"] = section_dicts
            self._commit_config()

    def rename(self, name: str) -> None:
        """Change the dashboard's display name."""
        response = self._rest_client.dashboards.update_dashboard(self._id, name=name)
        self._absorb(response)

    def set_description(self, description: str) -> None:
        """Set the dashboard's free-text description."""
        response = self._rest_client.dashboards.update_dashboard(
            self._id, description=description
        )
        self._absorb(response)

    def reload(self) -> None:
        """Re-fetch the dashboard from the backend, replacing all local state.

        Useful before a mutating operation when another client may have modified
        the dashboard since it was last loaded (last-writer-wins semantics).
        """
        response = self._rest_client.dashboards.get_dashboard_by_id(self._id)
        self._absorb(response)

    def delete(self) -> None:
        """Permanently delete the dashboard from the workspace."""
        self._rest_client.dashboards.delete_dashboard(self._id)

    @contextmanager
    def _atomic_config(self) -> Generator[None, None, None]:
        """Snapshot _config before a mutation; restore it if the commit fails."""
        old_config = copy.deepcopy(self._config)
        try:
            yield
        except Exception:
            self._config = old_config
            raise

    def _assert_config_writable(self) -> None:
        validation.validate_writable_version(self._config.get("version"))

    def _commit_config(self) -> None:
        self._config["version"] = types.DASHBOARD_VERSION
        self._config["lastModified"] = types.now_ms()
        validation.validate_structure(self._config)
        response = self._rest_client.dashboards.update_dashboard(
            self._id, config=self._config
        )
        self._absorb(response)

    def _default_section_id(self) -> str:
        sections = self._config.get("sections", [])
        if not sections:
            raise exceptions.DashboardValidationError(
                "Dashboard has no sections. Add a section first with add_section()."
            )
        return str(sections[0]["id"])

    def _get_section(self, section_id: str) -> Dict[str, Any]:
        for section in self._config.get("sections", []):
            if section.get("id") == section_id:
                return section
        raise exceptions.DashboardValidationError(
            f"Section with id {section_id!r} not found in dashboard"
        )

    def _find_widget(self, widget_id: str) -> Dict[str, Any]:
        for section in self._config.get("sections", []):
            for widget in section.get("widgets", []):
                if widget.get("id") == widget_id:
                    return widget
        raise exceptions.DashboardValidationError(
            f"Widget with id {widget_id!r} not found in dashboard"
        )
