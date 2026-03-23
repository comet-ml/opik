import { Filters } from "@/types/filters";
import { DateRangeSerializedValue } from "@/shared/DateRangeSelect";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { Groups } from "@/types/groups";
import { CHART_TYPE } from "@/constants/chart";
import { Sorting } from "@/types/sorting";

export enum BREAKDOWN_FIELD {
  NONE = "none",
  TAGS = "tags",
  METADATA = "metadata",
  NAME = "name",
  ERROR_INFO = "error_info",
  ERROR_TYPE = "error_type",
  MODEL = "model",
  PROVIDER = "provider",
  TYPE = "type",
}

export interface DashboardRuntimeConfig {
  dateRange?: DateRangeSerializedValue;
  projectIds?: string[];
  experimentIds?: string[];
  dashboardType?: DASHBOARD_TYPE;
}

export enum DASHBOARD_TYPE {
  MULTI_PROJECT = "multi_project",
  EXPERIMENTS = "experiments",
}

export const DASHBOARD_TYPE_LABELS: Record<DASHBOARD_TYPE, string> = {
  [DASHBOARD_TYPE.MULTI_PROJECT]: "Multi-project",
  [DASHBOARD_TYPE.EXPERIMENTS]: "Experiments",
};

export enum DASHBOARD_SCOPE {
  WORKSPACE = "workspace",
  INSIGHTS = "insights",
}

export enum WIDGET_TYPE {
  PROJECT_METRICS = "project_metrics",
  PROJECT_STATS_CARD = "project_stats_card",
  TEXT_MARKDOWN = "text_markdown",
  EXPERIMENTS_FEEDBACK_SCORES = "experiments_feedback_scores",
  EXPERIMENT_LEADERBOARD = "experiment_leaderboard",
}

export enum WIDGET_CATEGORY {
  OBSERVABILITY = "observability",
  EVALUATION = "evaluation",
  GENERAL = "general",
}

export enum TEMPLATE_TYPE {
  PROJECT_OVERVIEW = "project-overview",
  EXPERIMENT_COMPARISON = "experiment-comparison",
}

export enum TEMPLATE_SCOPE {
  PROJECT = "project",
  EXPERIMENTS = "experiments",
}

export interface BreakdownConfig {
  field: BREAKDOWN_FIELD;
  metadataKey?: string;
  subMetric?: string;
  aggregateTotal?: boolean;
}

export interface ProjectMetricsWidget {
  type: WIDGET_TYPE.PROJECT_METRICS;
  config: {
    projectId?: string;
    metricType: string;
    chartType?: CHART_TYPE.line | CHART_TYPE.bar;
    traceFilters?: Filters;
    threadFilters?: Filters;
    spanFilters?: Filters;
    feedbackScores?: string[];
    durationMetrics?: string[];
    usageMetrics?: string[];
    breakdown?: BreakdownConfig;
  } & Record<string, unknown>;
}

export interface TextMarkdownWidget {
  type: WIDGET_TYPE.TEXT_MARKDOWN;
  config: {
    content?: string;
  } & Record<string, unknown>;
}

export interface ProjectStatsCardWidget {
  type: WIDGET_TYPE.PROJECT_STATS_CARD;
  config: {
    source: TRACE_DATA_TYPE;
    projectId: string;
    metric: string;
    traceFilters?: Filters;
    spanFilters?: Filters;
  } & Record<string, unknown>;
}

export interface ExperimentsFeedbackScoresWidgetType {
  type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES;
  config: {
    filters?: Filters;
    groups?: Groups;
    chartType?: CHART_TYPE;
    feedbackScores?: string[];
    maxExperimentsCount?: number;
  } & Record<string, unknown>;
}

export interface ExperimentsLeaderboardWidgetType {
  type: WIDGET_TYPE.EXPERIMENT_LEADERBOARD;
  config: {
    filters?: Filters;
    selectedColumns?: string[];
    enableRanking?: boolean;
    rankingMetric?: string;
    rankingDirection?: boolean;
    columnsOrder?: string[];
    scoresColumnsOrder?: string[];
    metadataColumnsOrder?: string[];
    columnsWidth?: Record<string, number>;
    maxRows?: number;
    sorting?: Sorting;
  } & Record<string, unknown>;
}

type WidgetConfigUnion =
  | ProjectMetricsWidget
  | TextMarkdownWidget
  | ProjectStatsCardWidget
  | ExperimentsFeedbackScoresWidgetType
  | ExperimentsLeaderboardWidgetType
  | {
      type: string;
      config: Record<string, unknown>;
    };

export type DashboardWidget = {
  id: string;
  title: string;
  generatedTitle?: string;
  subtitle?: string;
} & WidgetConfigUnion;

export type WidgetSize = {
  w: number;
  h: number;
};

export type WidgetPosition = {
  x: number;
  y: number;
};

export type WidgetSizeConfig = WidgetSize & {
  minW: number;
  minH: number;
};

export interface DashboardLayoutItem extends WidgetPosition, WidgetSize {
  i: string;
  minW?: number;
  maxW?: number;
  minH?: number;
  maxH?: number;
}

export type DashboardLayout = DashboardLayoutItem[];

export interface DashboardSection {
  id: string;
  title: string;
  widgets: DashboardWidget[];
  layout: DashboardLayout;
}

export type DashboardSections = DashboardSection[];

export interface DashboardState {
  version: number;
  sections: DashboardSections;
  lastModified: number;
}

export interface Dashboard {
  id: string;
  name: string;
  description?: string;
  workspace_id: string;
  config: DashboardState;
  type: DASHBOARD_TYPE;
  scope: DASHBOARD_SCOPE;
  created_at: string;
  last_updated_at: string;
  created_by?: string;
}

export interface DashboardWidgetComponentProps {
  sectionId?: string;
  widgetId?: string;
  preview?: boolean;
}

export interface WidgetEditorHandle {
  submit: () => Promise<boolean>;
  isValid: boolean;
}

export type WidgetEditorComponent = React.ForwardRefExoticComponent<
  React.RefAttributes<WidgetEditorHandle>
>;

export interface WidgetMetadata {
  title: string;
  description: string;
  icon: React.ReactNode;
  category: WIDGET_CATEGORY;
  iconColor?: string;
  disabled?: boolean;
  disabledTooltip?: string;
}

export interface WidgetComponents {
  Widget: React.ComponentType<DashboardWidgetComponentProps>;
  Editor: WidgetEditorComponent | null;
  getDefaultConfig: () => Record<string, unknown>;
  calculateTitle: (config: Record<string, unknown>) => string;
  metadata: WidgetMetadata;
}

export type WidgetResolver = (type: string) => WidgetComponents;

export interface AddEditWidgetCallbackParams {
  sectionId: string;
  widgetId?: string | null;
}

export interface WidgetConfigDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  sectionId: string;
  widgetId?: string;
  onSave: (widget: DashboardWidget) => void;
}

export interface DashboardTemplate {
  id: string;
  type: TEMPLATE_TYPE;
  scope: TEMPLATE_SCOPE;
  name: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  iconColor: string;
  config: DashboardState;
}
