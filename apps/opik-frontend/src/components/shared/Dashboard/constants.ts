import { WIDGET_TYPE } from "@/types/dashboard";

export const DEFAULT_WIDGET_TITLES: Record<string, string> = {
  [WIDGET_TYPE.TEXT_MARKDOWN]: "Text Widget",
  [WIDGET_TYPE.CHART_METRIC]: "Project Metric Chart",
  [WIDGET_TYPE.STAT_CARD]: "Stat Card",
  [WIDGET_TYPE.COST_SUMMARY]: "Cost Summary",
};
