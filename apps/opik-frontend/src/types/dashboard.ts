import { Filters } from "@/types/filters";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { Groups } from "@/types/groups";
import { CHART_TYPE } from "@/constants/chart";

export enum WIDGET_TYPE {
  PROJECT_METRICS = "project_metrics",
  PROJECT_STATS_CARD = "project_stats_card",
  TEXT_MARKDOWN = "text_markdown",
  EXPERIMENTS_FEEDBACK_SCORES = "experiments_feedback_scores",
}

export enum EXPERIMENT_DATA_SOURCE {
  FILTER_AND_GROUP = "filter_and_group",
  SELECT_EXPERIMENTS = "select_experiments",
}

export enum WIDGET_CATEGORY {
  OBSERVABILITY = "observability",
  EVALUATION = "evaluation",
  GENERAL = "general",
}

export enum TEMPLATE_TYPE {
  PROJECT_METRICS = "project-metrics",
  PROJECT_PERFORMANCE = "project-performance",
  EXPERIMENT_COMPARISON = "experiment-comparison",
}

export enum TEMPLATE_SCOPE {
  PROJECT = "project",
  EXPERIMENTS = "experiments",
}

export interface ProjectMetricsWidget {
  type: WIDGET_TYPE.PROJECT_METRICS;
  config: {
    projectId?: string;
    metricType: string;
    chartType?: CHART_TYPE.line | CHART_TYPE.bar;
    traceFilters?: Filters;
    threadFilters?: Filters;
  } & Record<string, unknown>;
}

export interface TextMarkdownWidget {
  type: WIDGET_TYPE.TEXT_MARKDOWN;
  config?: {
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
    dataSource?: EXPERIMENT_DATA_SOURCE;
    filters?: Filters;
    groups?: Groups;
    experimentIds?: string[];
    chartType?: CHART_TYPE;
  } & Record<string, unknown>;
}

// Unified widget config type
export type AddWidgetConfig = {
  title: string;
  subtitle?: string;
} & (
  | ProjectMetricsWidget
  | TextMarkdownWidget
  | ProjectStatsCardWidget
  | ExperimentsFeedbackScoresWidgetType
);

// Update config with optional fields
export type UpdateWidgetConfig = {
  title?: string;
  subtitle?: string;
} & (
  | {
      type: WIDGET_TYPE.PROJECT_METRICS;
      config?: Partial<ProjectMetricsWidget["config"]>;
    }
  | {
      type: WIDGET_TYPE.TEXT_MARKDOWN;
      config?: Partial<NonNullable<TextMarkdownWidget["config"]>>;
    }
  | {
      type: WIDGET_TYPE.PROJECT_STATS_CARD;
      config?: Partial<ProjectStatsCardWidget["config"]>;
    }
  | {
      type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES;
      config?: Partial<ExperimentsFeedbackScoresWidgetType["config"]>;
    }
  | {
      type?: undefined;
      config?: Record<string, unknown>;
    }
);

// DashboardWidget extends AddWidgetConfig with id
export type DashboardWidget = {
  id: string;
  title: string;
  subtitle?: string;
} & (
  | ProjectMetricsWidget
  | TextMarkdownWidget
  | ProjectStatsCardWidget
  | ExperimentsFeedbackScoresWidgetType
  | {
      type: string;
      config: Record<string, unknown>;
    }
);

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
  config: BaseDashboardConfig;
}

export interface Dashboard {
  id: string;
  name: string;
  description?: string;
  workspace_id: string;
  config: DashboardState;
  created_at: string;
  last_updated_at: string;
  created_by?: string;
}

export interface BaseDashboardConfig {
  dateRange: DateRangeSerializedValue;
  projectIds: string[];
  experimentIds: string[];
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

export type WidgetEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
  onValidationChange?: (isValid: boolean) => void;
};

export type WidgetEditorComponent = React.ForwardRefExoticComponent<
  WidgetEditorProps & React.RefAttributes<WidgetEditorHandle>
>;

export interface WidgetMetadata {
  title: string;
  description: string;
  icon: React.ReactNode;
  category: WIDGET_CATEGORY;
  iconColor?: string;
  disabled?: boolean;
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
  onSave: (widgetData: Partial<DashboardWidget>) => void;
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
