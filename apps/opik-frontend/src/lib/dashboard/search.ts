import {
  DashboardSections,
  DashboardWidget,
  FilteredWidgetsMap,
} from "@/types/dashboard";

export const filterWidgetsBySearch = (
  widgets: DashboardWidget[],
  searchTerm: string,
): DashboardWidget[] => {
  if (!searchTerm.trim()) {
    return widgets;
  }

  const lowerSearch = searchTerm.toLowerCase();

  return widgets.filter((widget) => {
    const titleMatch = widget.title.toLowerCase().includes(lowerSearch);
    const typeMatch = widget.type.toLowerCase().includes(lowerSearch);

    return titleMatch || typeMatch;
  });
};

export const createWidgetFilterMap = (
  sections: DashboardSections,
  searchTerm: string,
): FilteredWidgetsMap => {
  if (!searchTerm.trim()) {
    return sections.reduce<FilteredWidgetsMap>((acc, section) => {
      acc[section.id] = undefined;
      return acc;
    }, {});
  }

  return sections.reduce<FilteredWidgetsMap>((acc, section) => {
    const filteredWidgets = filterWidgetsBySearch(section.widgets, searchTerm);

    acc[section.id] = filteredWidgets.reduce<Record<string, boolean>>(
      (widgetAcc, widget) => {
        widgetAcc[widget.id] = true;
        return widgetAcc;
      },
      {},
    );

    return acc;
  }, {});
};
