import { WidgetResolver, WidgetComponents } from "@/types/dashboard";
import ProjectMetricsWidget from "./ProjectMetricsWidget/ProjectMetricsWidget";
import ProjectMetricsEditor from "./ProjectMetricsWidget/ProjectMetricsEditor";
import TextMarkdownWidget from "./TextMarkdownWidget/TextMarkdownWidget";
import TextMarkdownEditor from "./TextMarkdownWidget/TextMarkdownEditor";
import StatCardWidget from "./StatCardWidget/StatCardWidget";
import StatCardEditor from "./StatCardWidget/StatCardEditor";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";

export const createWidgetResolver = (): WidgetResolver => {
  return (type: string): WidgetComponents => {
    switch (type) {
      case WIDGET_TYPES.CHART_METRIC:
        return {
          Widget: ProjectMetricsWidget,
          Editor: ProjectMetricsEditor,
          getDefaultConfig: () => ({
            chartType: "line" as const,
          }),
          calculateTitle: () => "",
        };
      case WIDGET_TYPES.TEXT_MARKDOWN:
        return {
          Widget: TextMarkdownWidget,
          Editor: TextMarkdownEditor,
          getDefaultConfig: () => ({}),
          calculateTitle: () => "",
        };
      case WIDGET_TYPES.STAT_CARD:
        return {
          Widget: StatCardWidget,
          Editor: StatCardEditor,
          getDefaultConfig: () => ({
            source: "traces" as const,
          }),
          calculateTitle: () => "",
        };
      default:
        return {
          Widget: ProjectMetricsWidget,
          Editor: null,
          getDefaultConfig: () => ({}),
          calculateTitle: () => "",
        };
    }
  };
};
