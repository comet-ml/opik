import { NotebookText, Hash, Trophy, ChartNoAxesCombined } from "lucide-react";
import {
  WidgetResolver,
  WidgetComponents,
  WIDGET_CATEGORY,
} from "@/types/dashboard";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";
import ProjectMetricsWidget from "./ProjectMetricsWidget/ProjectMetricsWidget";
import ProjectMetricsEditor from "./ProjectMetricsWidget/ProjectMetricsEditor";
import { widgetHelpers as projectMetricsHelpers } from "./ProjectMetricsWidget/helpers";
import TextMarkdownWidget from "./TextMarkdownWidget/TextMarkdownWidget";
import TextMarkdownEditor from "./TextMarkdownWidget/TextMarkdownEditor";
import { widgetHelpers as textMarkdownHelpers } from "./TextMarkdownWidget/helpers";
import ProjectStatsCardWidget from "./ProjectStatsCardWidget/ProjectStatsCardWidget";
import ProjectStatsCardEditor from "./ProjectStatsCardWidget/ProjectStatsCardEditor";
import { widgetHelpers as projectStatsCardHelpers } from "./ProjectStatsCardWidget/helpers";
import ExperimentsFeedbackScoresWidget from "./ExperimentsFeedbackScoresWidget/ExperimentsFeedbackScoresWidget";
import ExperimentsFeedbackScoresWidgetEditor from "./ExperimentsFeedbackScoresWidget/ExperimentsFeedbackScoresWidgetEditor";
import { widgetHelpers as experimentsFeedbackScoresHelpers } from "./ExperimentsFeedbackScoresWidget/helpers";
import ExperimentsLeaderboardWidget from "./ExperimentsLeaderboardWidget/ExperimentsLeaderboardWidget";
import ExperimentsLeaderboardWidgetEditor from "./ExperimentsLeaderboardWidget/ExperimentsLeaderboardWidgetEditor";
import { widgetHelpers as experimentLeaderboardHelpers } from "./ExperimentsLeaderboardWidget/helpers";

export const widgetResolver: WidgetResolver = (
  type: string,
): WidgetComponents => {
  switch (type) {
    case WIDGET_TYPES.PROJECT_METRICS:
      return {
        Widget: ProjectMetricsWidget,
        Editor: ProjectMetricsEditor,
        getDefaultConfig: projectMetricsHelpers.getDefaultConfig,
        calculateTitle: projectMetricsHelpers.calculateTitle,
        metadata: {
          title: "Time series",
          description: "Visualize trends in project metrics over time.",
          icon: <ChartNoAxesCombined className="size-4" />,
          category: WIDGET_CATEGORY.OBSERVABILITY,
          iconColor: "text-chart-blue",
          disabled: false,
        },
      };
    case WIDGET_TYPES.TEXT_MARKDOWN:
      return {
        Widget: TextMarkdownWidget,
        Editor: TextMarkdownEditor,
        getDefaultConfig: textMarkdownHelpers.getDefaultConfig,
        calculateTitle: textMarkdownHelpers.calculateTitle,
        metadata: {
          title: "Markdown",
          description:
            "Add markdown or text for explanations, labels, or annotations.",
          icon: <NotebookText className="size-4" />,
          category: WIDGET_CATEGORY.GENERAL,
          iconColor: "text-chart-red",
          disabled: false,
        },
      };
    case WIDGET_TYPES.PROJECT_STATS_CARD:
      return {
        Widget: ProjectStatsCardWidget,
        Editor: ProjectStatsCardEditor,
        getDefaultConfig: projectStatsCardHelpers.getDefaultConfig,
        calculateTitle: projectStatsCardHelpers.calculateTitle,
        metadata: {
          title: "Single metric",
          description: "Highlight key project numbers at a glance.",
          icon: <Hash className="size-4" />,
          category: WIDGET_CATEGORY.OBSERVABILITY,
          iconColor: "text-chart-green",
          disabled: false,
        },
      };
    case WIDGET_TYPES.EXPERIMENTS_FEEDBACK_SCORES:
      return {
        Widget: ExperimentsFeedbackScoresWidget,
        Editor: ExperimentsFeedbackScoresWidgetEditor,
        getDefaultConfig: experimentsFeedbackScoresHelpers.getDefaultConfig,
        calculateTitle: experimentsFeedbackScoresHelpers.calculateTitle,
        metadata: {
          title: "Metrics",
          description: "Visualize experiment metrics over time or across runs.",
          icon: <ChartNoAxesCombined className="size-4" />,
          category: WIDGET_CATEGORY.EVALUATION,
          iconColor: "text-chart-blue",
          disabled: false,
        },
      };
    case WIDGET_TYPES.EXPERIMENT_LEADERBOARD:
      return {
        Widget: ExperimentsLeaderboardWidget,
        Editor: ExperimentsLeaderboardWidgetEditor,
        getDefaultConfig: experimentLeaderboardHelpers.getDefaultConfig,
        calculateTitle: experimentLeaderboardHelpers.calculateTitle,
        metadata: {
          title: "Leaderboard",
          description:
            "Rank and compare experiments across multiple metrics in a sortable table.",
          icon: <Trophy className="size-4" />,
          category: WIDGET_CATEGORY.EVALUATION,
          iconColor: "text-chart-yellow",
          disabled: false,
        },
      };
    default:
      return {
        Widget: TextMarkdownWidget,
        Editor: TextMarkdownEditor,
        getDefaultConfig: textMarkdownHelpers.getDefaultConfig,
        calculateTitle: textMarkdownHelpers.calculateTitle,
        metadata: {
          title: "Markdown",
          description:
            "Add markdown or text for explanations, labels, or annotations.",
          icon: <NotebookText className="size-4" />,
          category: WIDGET_CATEGORY.GENERAL,
          iconColor: "text-chart-red",
          disabled: false,
        },
      };
  }
};
