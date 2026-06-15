import copy
import logging
from typing import Any, Dict, List, Optional, Union

from opik import exceptions, id_helpers
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import dashboard_public as rest_dashboard_public

from . import layout, types, validation

LOGGER = logging.getLogger(__name__)


class Dashboard:
    """A high-level wrapper around an Opik dashboard.

    Do not instantiate directly; use :meth:`opik.Opik.create_dashboard`,
    :meth:`opik.Opik.get_dashboard`, or :meth:`opik.Opik.find_dashboards`.

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
        self._config: Dict[str, Any] = copy.deepcopy(dashboard_public.config or {})
        self._created_at = dashboard_public.created_at
        self._last_updated_at = dashboard_public.last_updated_at

    @property
    def id(self) -> str:
        """The id of the dashboard."""
        return self._id

    @property
    def name(self) -> str:
        """The name of the dashboard."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the dashboard."""
        return self._description

    @property
    def type(self) -> Optional[str]:
        """The dashboard type (``multi_project`` or ``experiments``)."""
        return self._type

    @property
    def scope(self) -> Optional[str]:
        """The dashboard scope (read-only; set by the backend)."""
        return self._scope

    @property
    def config(self) -> Dict[str, Any]:
        """A copy of the raw ``config`` blob."""
        return copy.deepcopy(self._config)

    @property
    def state(self) -> types.DashboardState:
        """A typed, read-only projection of the ``config`` blob."""
        return types.DashboardState.model_validate(self._config)

    @property
    def sections(self) -> List[types.DashboardSection]:
        """The typed sections of the dashboard (read-only projection)."""
        return self.state.sections

    def add_widget(
        self,
        section_id: str,
        widget: Union[types.DashboardWidget, Dict[str, Any]],
        *,
        size: Optional[Dict[str, int]] = None,
    ) -> str:
        """Add a widget to a section and auto-place it on the grid.

        Args:
            section_id: The id of the section to add the widget to.
            widget: A :class:`~opik.api_objects.dashboard.types.DashboardWidget`
                or a raw config dict.
            size: Optional ``{"w": int, "h": int}`` override for the widget size.

        Returns:
            The id of the added widget.
        """
        self._assert_config_writable()
        widget_dict = copy.deepcopy(validation.as_widget_dict(widget))
        widget_dict.setdefault("id", id_helpers.generate_id())
        validation.validate_widget_for_dashboard(widget_dict, self._type)

        section = self._get_section(section_id)
        section.setdefault("widgets", []).append(widget_dict)
        section["layout"] = layout.calculate_layout_for_adding_widget(
            section.get("layout", []),
            widget_type=str(widget_dict["type"]),
            widget_id=str(widget_dict["id"]),
            size=size,
        )

        self._commit_config()
        return str(widget_dict["id"])

    def remove_widget(self, widget_id: str) -> None:
        """Remove a widget (and its layout item) from whichever section holds it."""
        self._assert_config_writable()
        removed = False
        for section in self._config.get("sections", []):
            widgets = section.get("widgets", [])
            kept = [w for w in widgets if w.get("id") != widget_id]
            if len(kept) != len(widgets):
                removed = True
                section["widgets"] = kept
                section["layout"] = layout.remove_widget_from_layout(
                    section.get("layout", []), widget_id
                )

        if not removed:
            raise exceptions.DashboardValidationError(
                f"Widget with id {widget_id!r} not found in dashboard"
            )

        self._commit_config()

    def update_widget(
        self,
        widget_id: str,
        *,
        title: Optional[str] = None,
        subtitle: Optional[str] = None,
        config: Optional[Union[types._DashboardModel, Dict[str, Any]]] = None,
    ) -> None:
        """Update a widget's title, subtitle, and/or merge into its config."""
        self._assert_config_writable()
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
            widget.setdefault("config", {}).update(config_dict)

        validation.validate_widget_for_dashboard(widget, self._type)
        self._commit_config()

    def add_section(self, title: str) -> str:
        """Append a new empty section and return its id."""
        self._assert_config_writable()
        section = types.DashboardSection(title=title).to_jsonable()
        self._config.setdefault("sections", []).append(section)
        self._commit_config()
        return str(section["id"])

    def replace_sections(
        self,
        sections: List[Union[types.DashboardSection, Dict[str, Any]]],
    ) -> None:
        """Replace all sections wholesale."""
        self._assert_config_writable()
        section_dicts = copy.deepcopy(validation.as_section_dicts(sections))
        for section in section_dicts:
            for widget in section.get("widgets", []):
                validation.validate_widget_for_dashboard(widget, self._type)
        self._config["sections"] = section_dicts
        self._commit_config()

    def rename(self, name: str) -> None:
        """Rename the dashboard."""
        response = self._rest_client.dashboards.update_dashboard(self._id, name=name)
        self._absorb(response)

    def set_description(self, description: str) -> None:
        """Set the dashboard description."""
        response = self._rest_client.dashboards.update_dashboard(
            self._id, description=description
        )
        self._absorb(response)

    def reload(self) -> None:
        """Re-fetch the dashboard from the backend, discarding unsaved local edits."""
        response = self._rest_client.dashboards.get_dashboard_by_id(self._id)
        self._absorb(response)

    def delete(self) -> None:
        """Delete the dashboard."""
        self._rest_client.dashboards.delete_dashboard(self._id)

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
