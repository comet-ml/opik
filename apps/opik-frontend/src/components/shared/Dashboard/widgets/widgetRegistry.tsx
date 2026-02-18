import {
  NotebookText,
  ChartLine,
  Hash,
  FlaskConical,
  Trophy,
} from "lucide-react";
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

export const DISABLED_EXPERIMENTS_TOOLTIP =
  "You don't have permission to view experiments";

export const widgetResolver: WidgetResolver = ({
  type,
  canViewExperiments = true,
}): WidgetComponents => {
  switch (type) {
    case WIDGET_TYPES.PROJECT_METRICS:
      return {
        Widget: ProjectMetricsWidget,
        Editor: ProjectMetricsEditor,
        getDefaultConfig: projectMetricsHelpers.getDefaultConfig,
        calculateTitle: projectMetricsHelpers.calculateTitle,
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
        getDefaultConfig: textMarkdownHelpers.getDefaultConfig,
        calculateTitle: textMarkdownHelpers.calculateTitle,
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
        getDefaultConfig: projectStatsCardHelpers.getDefaultConfig,
        calculateTitle: projectStatsCardHelpers.calculateTitle,
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
        getDefaultConfig: experimentsFeedbackScoresHelpers.getDefaultConfig,
        calculateTitle: experimentsFeedbackScoresHelpers.calculateTitle,
        metadata: {
          title: "Experiments metrics",
          description:
            "Visualize experiment metrics with filtering and grouping options.",
          icon: <FlaskConical className="size-4" />,
          category: WIDGET_CATEGORY.EVALUATION,
          iconColor: "text-[#9B59B6]",
          disabled: !canViewExperiments,
          disabledTooltip: canViewExperiments
            ? undefined
            : DISABLED_EXPERIMENTS_TOOLTIP,
        },
      };
    case WIDGET_TYPES.EXPERIMENT_LEADERBOARD:
      return {
        Widget: ExperimentsLeaderboardWidget,
        Editor: ExperimentsLeaderboardWidgetEditor,
        getDefaultConfig: experimentLeaderboardHelpers.getDefaultConfig,
        calculateTitle: experimentLeaderboardHelpers.calculateTitle,
        metadata: {
          title: "Experiment leaderboard",
          description:
            "Rank and compare experiments across multiple metrics in a sortable table.",
          icon: <Trophy className="size-4" />,
          category: WIDGET_CATEGORY.EVALUATION,
          iconColor: "text-[#FFD700]",
          disabled: !canViewExperiments,
          disabledTooltip: canViewExperiments
            ? undefined
            : DISABLED_EXPERIMENTS_TOOLTIP,
        },
      };
    default:
      return {
        Widget: TextMarkdownWidget,
        Editor: TextMarkdownEditor,
        getDefaultConfig: textMarkdownHelpers.getDefaultConfig,
        calculateTitle: textMarkdownHelpers.calculateTitle,
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

export const getAllWidgetTypes = (): string[] => {
  return [
    WIDGET_TYPES.PROJECT_METRICS,
    WIDGET_TYPES.PROJECT_STATS_CARD,
    WIDGET_TYPES.EXPERIMENTS_FEEDBACK_SCORES,
    WIDGET_TYPES.EXPERIMENT_LEADERBOARD,
    WIDGET_TYPES.TEXT_MARKDOWN,
  ];
};
