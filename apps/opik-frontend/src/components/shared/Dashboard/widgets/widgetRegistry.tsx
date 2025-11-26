import { WidgetResolver, WidgetComponents } from "@/types/dashboard";
import ProjectMetricsWidget from "./ProjectMetricsWidget/ProjectMetricsWidget";
import ProjectMetricsEditor from "./ProjectMetricsWidget/ProjectMetricsEditor";
import TextMarkdownWidget from "./TextMarkdownWidget/TextMarkdownWidget";
import TextMarkdownEditor from "./TextMarkdownWidget/TextMarkdownEditor";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";

export const createWidgetResolver = (): WidgetResolver => {
  return (type: string): WidgetComponents => {
    switch (type) {
      case WIDGET_TYPES.CHART_METRIC:
        return {
          Widget: ProjectMetricsWidget,
          Editor: ProjectMetricsEditor,
        };
      case WIDGET_TYPES.TEXT_MARKDOWN:
        return {
          Widget: TextMarkdownWidget,
          Editor: TextMarkdownEditor,
        };
      default:
        return {
          Widget: ProjectMetricsWidget,
          Editor: null,
        };
    }
  };
};
