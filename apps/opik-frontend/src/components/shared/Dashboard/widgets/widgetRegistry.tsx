import { NotebookText, ChartLine, Hash, FlaskConical } from "lucide-react";
import {
  WidgetResolver,
  WidgetComponents,
  WIDGET_CATEGORY,
} from "@/types/dashboard";
import { CHART_TYPE } from "@/constants/chart";
import ProjectMetricsWidget from "./ProjectMetricsWidget/ProjectMetricsWidget";
import ProjectMetricsEditor from "./ProjectMetricsWidget/ProjectMetricsEditor";
import TextMarkdownWidget from "./TextMarkdownWidget/TextMarkdownWidget";
import TextMarkdownEditor from "./TextMarkdownWidget/TextMarkdownEditor";
import ProjectStatsCardWidget from "./ProjectStatsCardWidget/ProjectStatsCardWidget";
import ProjectStatsCardEditor from "./ProjectStatsCardWidget/ProjectStatsCardEditor";
import ExperimentsFeedbackScoresWidget from "./ExperimentsFeedbackScoresWidget/ExperimentsFeedbackScoresWidget";
import ExperimentsFeedbackScoresWidgetEditor from "./ExperimentsFeedbackScoresWidget/ExperimentsFeedbackScoresWidgetEditor";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";

export const createWidgetResolver = (): WidgetResolver => {
  return (type: string): WidgetComponents => {
    switch (type) {
      case WIDGET_TYPES.PROJECT_METRICS:
        return {
          Widget: ProjectMetricsWidget,
          Editor: ProjectMetricsEditor,
          getDefaultConfig: () => ({
            chartType: CHART_TYPE.line,
          }),
          calculateTitle: () => "",
          metadata: {
            title: "Project metrics",
            description:
              "Visualize project metrics like latency, cost, or usage over time using charts.",
            icon: <ChartLine className="size-4" />,
            category: WIDGET_CATEGORY.OBSERVABILITY,
            iconColor: "text-[#5899DA]",
            disabled: false,
          },
        };
      case WIDGET_TYPES.TEXT_MARKDOWN:
        return {
          Widget: TextMarkdownWidget,
          Editor: TextMarkdownEditor,
          getDefaultConfig: () => ({}),
          calculateTitle: () => "",
          metadata: {
            title: "Markdown text",
            description:
              "Add markdown or text for explanations, labels, or annotations.",
            icon: <NotebookText className="size-4" />,
            category: WIDGET_CATEGORY.GENERAL,
            iconColor: "text-[#EF6868]",
            disabled: false,
          },
        };
      case WIDGET_TYPES.PROJECT_STATS_CARD:
        return {
          Widget: ProjectStatsCardWidget,
          Editor: ProjectStatsCardEditor,
          getDefaultConfig: () => ({
            source: "traces" as const,
          }),
          calculateTitle: () => "",
          metadata: {
            title: "Project statistics",
            description:
              "Highlight key project numbers at a glance, such as average latency, or total cost.",
            icon: <Hash className="size-4" />,
            category: WIDGET_CATEGORY.OBSERVABILITY,
            iconColor: "text-[#19A979]",
            disabled: false,
          },
        };
      case WIDGET_TYPES.EXPERIMENTS_FEEDBACK_SCORES:
        return {
          Widget: ExperimentsFeedbackScoresWidget,
          Editor: ExperimentsFeedbackScoresWidgetEditor,
          getDefaultConfig: () => ({
            filters: [],
            groups: [],
            chartType: CHART_TYPE.line,
          }),
          calculateTitle: () => "",
          metadata: {
            title: "Experiments feedback scores",
            description:
              "Visualize experiment feedback scores with filtering and grouping options.",
            icon: <FlaskConical className="size-4" />,
            category: WIDGET_CATEGORY.EVALUATION,
            iconColor: "text-[#9B59B6]",
            disabled: false,
          },
        };
      default:
        return {
          Widget: TextMarkdownWidget,
          Editor: TextMarkdownEditor,
          getDefaultConfig: () => ({}),
          calculateTitle: () => "",
          metadata: {
            title: "Markdown text",
            description:
              "Add markdown or text for explanations, labels, or annotations.",
            icon: <NotebookText className="size-4" />,
            category: WIDGET_CATEGORY.GENERAL,
            iconColor: "text-[#EF6868]",
            disabled: false,
          },
        };
    }
  };
};

export const getAllWidgetTypes = (): string[] => {
  return [
    WIDGET_TYPES.PROJECT_METRICS,
    WIDGET_TYPES.PROJECT_STATS_CARD,
    WIDGET_TYPES.EXPERIMENTS_FEEDBACK_SCORES,
    WIDGET_TYPES.TEXT_MARKDOWN,
  ];
};
