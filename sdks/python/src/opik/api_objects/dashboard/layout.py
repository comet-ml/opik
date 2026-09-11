"""Grid auto-layout, ported from apps/opik-frontend/src/lib/dashboard/layout.ts.

Operates on :class:`~opik.api_objects.dashboard.types.DashboardLayoutItem` objects.
Keeping the algorithm in sync with the frontend means widgets the SDK adds are
positioned the same way the UI would position them.
"""

from typing import Any, Dict, List, Optional

from . import types
from .types import DashboardLayoutItem

GRID_COLUMNS = types.GRID_COLUMNS
MAX_WIDGET_HEIGHT = types.MAX_WIDGET_HEIGHT
MIN_WIDGET_WIDTH = types.MIN_WIDGET_WIDTH
MIN_WIDGET_HEIGHT = types.MIN_WIDGET_HEIGHT

_WIDGET_SIZE_CONFIG: Dict[str, Dict[str, int]] = {
    types.WidgetType.PROJECT_METRICS.value: {"w": 2, "h": 4, "minW": 2, "minH": 4},
    types.WidgetType.PROJECT_STATS_CARD.value: {"w": 1, "h": 2, "minW": 1, "minH": 2},
    types.WidgetType.TEXT_MARKDOWN.value: {"w": 2, "h": 4, "minW": 1, "minH": 4},
    types.WidgetType.EXPERIMENTS_FEEDBACK_SCORES.value: {
        "w": 2,
        "h": 4,
        "minW": 2,
        "minH": 4,
    },
    types.WidgetType.EXPERIMENT_LEADERBOARD.value: {
        "w": 6,
        "h": 6,
        "minW": 4,
        "minH": 4,
    },
}

_DEFAULT_SIZE_CONFIG = {
    "w": 2,
    "h": 2,
    "minW": MIN_WIDGET_WIDTH,
    "minH": MIN_WIDGET_HEIGHT,
}


def get_widget_size_config(widget_type: str) -> Dict[str, int]:
    return _WIDGET_SIZE_CONFIG.get(widget_type, _DEFAULT_SIZE_CONFIG)


def get_column_heights(layout: List[DashboardLayoutItem]) -> List[int]:
    heights = [0] * GRID_COLUMNS

    for item in layout:
        start_col = item.x
        end_col = min(item.x + item.w, GRID_COLUMNS)
        item_bottom = item.y + item.h

        for col in range(start_col, end_col):
            heights[col] = max(heights[col], item_bottom)

    return heights


def find_first_available_position(
    w: int, h: int, column_heights: List[int]
) -> Dict[str, int]:
    # The frontend leaves w unclamped, which makes the search range empty (and y
    # Infinity) for w > GRID_COLUMNS. Clamp here so the result is always a finite
    # placement; real widget sizes never exceed the grid width.
    w = min(w, GRID_COLUMNS)

    min_height = float("inf")
    best_x = 0

    for x in range(0, GRID_COLUMNS - w + 1):
        max_height_in_range = max(column_heights[x : x + w])
        if max_height_in_range < min_height:
            min_height = max_height_in_range
            best_x = x

    return {"x": best_x, "y": int(min_height)}


def calculate_layout_for_adding_widget(
    layout: List[DashboardLayoutItem],
    widget_type: str,
    widget_id: str,
    size: Optional[Dict[str, int]] = None,
) -> List[DashboardLayoutItem]:
    size_config = get_widget_size_config(widget_type)
    raw_w = size["w"] if size else size_config["w"]
    raw_h = size["h"] if size else size_config["h"]
    w = max(size_config["minW"], min(raw_w, GRID_COLUMNS))
    h = max(size_config["minH"], min(raw_h, MAX_WIDGET_HEIGHT))

    new_item = DashboardLayoutItem(
        id=widget_id,
        x=0,
        y=0,
        w=w,
        h=h,
        min_w=size_config["minW"],
        min_h=size_config["minH"],
        max_w=GRID_COLUMNS,
        max_h=MAX_WIDGET_HEIGHT,
    )

    if not layout:
        return [new_item]

    column_heights = get_column_heights(layout)
    position = find_first_available_position(w, h, column_heights)
    new_item.x = position["x"]
    new_item.y = position["y"]

    return [*layout, new_item]


def normalize_layout(
    layout: List[DashboardLayoutItem],
    widgets: Optional[List[Dict[str, Any]]] = None,
) -> List[DashboardLayoutItem]:
    widgets_by_id = {w["id"]: w for w in (widgets or [])}

    normalized: List[DashboardLayoutItem] = []
    for item in layout:
        widget = widgets_by_id.get(item.id)
        if widget is not None:
            size_config = get_widget_size_config(str(widget["type"]))
            min_w, min_h = size_config["minW"], size_config["minH"]
        else:
            min_w, min_h = MIN_WIDGET_WIDTH, MIN_WIDGET_HEIGHT

        normalized.append(
            DashboardLayoutItem(
                id=item.id,
                x=max(0, min(item.x, GRID_COLUMNS - item.w)),
                y=max(0, item.y),
                w=max(min_w, min(item.w, GRID_COLUMNS)),
                h=max(min_h, min(item.h, MAX_WIDGET_HEIGHT)),
                min_w=min_w,
                min_h=min_h,
                max_w=GRID_COLUMNS,
                max_h=MAX_WIDGET_HEIGHT,
            )
        )

    return normalized


def remove_widget_from_layout(
    layout: List[DashboardLayoutItem], widget_id: str
) -> List[DashboardLayoutItem]:
    return [item for item in layout if item.id != widget_id]
