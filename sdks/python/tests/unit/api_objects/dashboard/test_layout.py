"""Ported from apps/opik-frontend/src/lib/dashboard/layout.test.ts.

Keeps the SDK auto-layout behavior identical to the frontend so widgets added
via the SDK are positioned the same way the UI would position them.
"""

import pytest

from opik.api_objects.dashboard import layout
from opik.api_objects.dashboard.types import DashboardLayoutItem, WidgetType


def _item(id: str, x: int, y: int, w: int, h: int) -> DashboardLayoutItem:
    return DashboardLayoutItem(id=id, x=x, y=y, w=w, h=h)


def test_grid_constants__match_frontend():
    assert layout.GRID_COLUMNS == 6
    assert layout.MAX_WIDGET_HEIGHT == 12
    assert layout.MIN_WIDGET_WIDTH == 1
    assert layout.MIN_WIDGET_HEIGHT == 1


@pytest.mark.parametrize(
    "widget_type,expected",
    [
        (WidgetType.PROJECT_METRICS.value, {"w": 2, "h": 4, "minW": 2, "minH": 4}),
        (WidgetType.PROJECT_STATS_CARD.value, {"w": 1, "h": 2, "minW": 1, "minH": 2}),
        (WidgetType.TEXT_MARKDOWN.value, {"w": 2, "h": 4, "minW": 1, "minH": 4}),
        ("unknown-type", {"w": 2, "h": 2, "minW": 1, "minH": 1}),
    ],
)
def test_get_widget_size_config__per_type(widget_type, expected):
    assert layout.get_widget_size_config(widget_type) == expected


@pytest.mark.parametrize(
    "layout_items,expected",
    [
        ([], [0, 0, 0, 0, 0, 0]),
        ([_item("w1", 0, 0, 2, 3)], [3, 3, 0, 0, 0, 0]),
        (
            [_item("w1", 0, 0, 2, 3), _item("w2", 2, 0, 2, 5)],
            [3, 3, 5, 5, 0, 0],
        ),
        (
            [_item("w1", 0, 0, 2, 3), _item("w2", 1, 3, 2, 2)],
            [3, 5, 5, 0, 0, 0],
        ),
        ([_item("w1", 0, 0, 6, 4)], [4, 4, 4, 4, 4, 4]),
        (
            [_item("w1", 0, 2, 2, 3), _item("w2", 2, 0, 2, 2)],
            [5, 5, 2, 2, 0, 0],
        ),
    ],
)
def test_get_column_heights(layout_items, expected):
    assert layout.get_column_heights(layout_items) == expected


@pytest.mark.parametrize(
    "w,h,heights,expected",
    [
        (2, 3, [0, 0, 0, 0, 0, 0], {"x": 0, "y": 0}),
        (2, 2, [3, 3, 0, 0, 0, 0], {"x": 2, "y": 0}),
        (2, 2, [3, 3, 5, 5, 4, 4], {"x": 0, "y": 3}),
        (6, 1, [2, 2, 3, 3, 2, 2], {"x": 0, "y": 3}),
        (2, 3, [5, 5, 2, 2, 8, 8], {"x": 2, "y": 2}),
        (1, 2, [3, 0, 2, 4, 1, 5], {"x": 1, "y": 0}),
    ],
)
def test_find_first_available_position(w, h, heights, expected):
    assert layout.find_first_available_position(w, h, heights) == expected


def test_calculate_layout_for_adding_widget__first_widget_to_empty_layout():
    result = layout.calculate_layout_for_adding_widget(
        [], WidgetType.TEXT_MARKDOWN.value, "widget-1"
    )
    assert len(result) == 1
    assert result[0].id == "widget-1"
    assert (result[0].x, result[0].y, result[0].w, result[0].h) == (0, 0, 2, 4)


def test_calculate_layout_for_adding_widget__custom_size():
    result = layout.calculate_layout_for_adding_widget(
        [], WidgetType.TEXT_MARKDOWN.value, "widget-1", size={"w": 3, "h": 5}
    )
    assert result[0].w == 3
    assert result[0].h == 5


def test_calculate_layout_for_adding_widget__clamps_oversized_custom_size():
    result = layout.calculate_layout_for_adding_widget(
        [],
        WidgetType.TEXT_MARKDOWN.value,
        "widget-1",
        size={"w": 100, "h": 100},
    )
    assert result[0].w == layout.GRID_COLUMNS
    assert result[0].h == layout.MAX_WIDGET_HEIGHT


def test_calculate_layout_for_adding_widget__clamps_undersized_custom_size():
    result = layout.calculate_layout_for_adding_widget(
        [],
        WidgetType.PROJECT_METRICS.value,
        "widget-1",
        size={"w": 1, "h": 1},
    )
    size_config = layout.get_widget_size_config(WidgetType.PROJECT_METRICS.value)
    assert result[0].w == size_config["minW"]
    assert result[0].h == size_config["minH"]


def test_calculate_layout_for_adding_widget__second_widget_placed_next_to_first():
    existing = [_item("widget-1", 0, 0, 2, 4)]
    result = layout.calculate_layout_for_adding_widget(
        existing, WidgetType.TEXT_MARKDOWN.value, "widget-2"
    )
    assert len(result) == 2
    assert result[1].id == "widget-2"
    assert (result[1].x, result[1].y) == (2, 0)


def test_calculate_layout_for_adding_widget__sets_min_and_max_constraints():
    result = layout.calculate_layout_for_adding_widget(
        [], WidgetType.PROJECT_METRICS.value, "widget-1"
    )
    assert result[0].min_w == 2
    assert result[0].min_h == 4
    assert result[0].max_w == layout.GRID_COLUMNS
    assert result[0].max_h == layout.MAX_WIDGET_HEIGHT


def test_calculate_layout_for_adding_widget__optimal_position_with_complex_layout():
    existing = [_item("widget-1", 0, 0, 3, 4), _item("widget-2", 3, 0, 3, 2)]
    result = layout.calculate_layout_for_adding_widget(
        existing, WidgetType.PROJECT_STATS_CARD.value, "widget-3"
    )
    assert result[2].y == 2


def test_normalize_layout__empty():
    assert layout.normalize_layout([]) == []


def test_normalize_layout__clamps_position_to_grid_bounds():
    normalized = layout.normalize_layout([_item("w1", 7, -1, 2, 3)])
    assert normalized[0].x == 4
    assert normalized[0].y == 0


def test_normalize_layout__clamps_width_to_grid_columns():
    normalized = layout.normalize_layout([_item("w1", 0, 0, 10, 3)])
    assert normalized[0].w == layout.GRID_COLUMNS


def test_normalize_layout__clamps_height_to_max():
    normalized = layout.normalize_layout([_item("w1", 0, 0, 2, 20)])
    assert normalized[0].h == layout.MAX_WIDGET_HEIGHT


def test_normalize_layout__enforces_minimum_dimensions():
    normalized = layout.normalize_layout([_item("w1", 0, 0, 0, 0)])
    assert normalized[0].w == layout.MIN_WIDGET_WIDTH
    assert normalized[0].h == layout.MIN_WIDGET_HEIGHT


def test_normalize_layout__applies_widget_specific_constraints():
    widgets = [{"id": "w1", "type": WidgetType.PROJECT_METRICS.value}]
    normalized = layout.normalize_layout([_item("w1", 0, 0, 1, 2)], widgets)
    assert normalized[0].min_w == 2
    assert normalized[0].min_h == 4
    assert normalized[0].w == 2
    assert normalized[0].h == 4


def test_remove_widget_from_layout():
    items = [_item("w1", 0, 0, 2, 3), _item("w2", 2, 0, 2, 3), _item("w3", 4, 0, 2, 3)]
    result = layout.remove_widget_from_layout(items, "w2")
    assert [item.id for item in result] == ["w1", "w3"]
    # original not mutated
    assert len(items) == 3


def test_remove_widget_from_layout__non_existent_id_is_noop():
    items = [_item("w1", 0, 0, 2, 3)]
    result = layout.remove_widget_from_layout(items, "ghost")
    assert len(result) == 1
    assert result[0].id == "w1"
