from typing import TYPE_CHECKING, List, Optional

from opik.rest_api import client as rest_api_client

from . import dashboard

if TYPE_CHECKING:
    from opik.api_objects import opik_client

_PAGE_SIZE = 100


def find_dashboards(
    rest_client: rest_api_client.OpikApi,
    client: "opik_client.Opik",
    name: Optional[str] = None,
    project_id: Optional[str] = None,
    max_results: int = 100,
    sorting: Optional[str] = None,
    filters: Optional[str] = None,
) -> List[dashboard.Dashboard]:
    dashboards: List[dashboard.Dashboard] = []
    page = 1

    while len(dashboards) < max_results:
        page_data = rest_client.dashboards.find_dashboards(
            page=page,
            size=_PAGE_SIZE,
            name=name,
            project_id=project_id,
            sorting=sorting,
            filters=filters,
        )

        content = page_data.content or []
        if not content:
            break

        for dashboard_public in content[: max_results - len(dashboards)]:
            dashboards.append(
                dashboard.Dashboard.from_public(
                    dashboard_public=dashboard_public,
                    rest_client=rest_client,
                    client=client,
                )
            )

        if len(content) < _PAGE_SIZE:
            break

        page += 1

    return dashboards
