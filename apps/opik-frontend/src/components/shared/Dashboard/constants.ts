import { WIDGET_TYPE } from "@/types/dashboard";

export const DEFAULT_WIDGET_TITLES: Record<string, string> = {
  [WIDGET_TYPE.TEXT_MARKDOWN]: "Text widget",
  [WIDGET_TYPE.CHART_METRIC]: "Project metric chart",
  [WIDGET_TYPE.STAT_CARD]: "Stat card",
  [WIDGET_TYPE.COST_SUMMARY]: "Cost summary",
};
