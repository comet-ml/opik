import { Filter } from "@/types/filters";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";

export enum WIDGET_TYPE {
  CHART_METRIC = "chart",
  STAT_CARD = "stat_card",
  TEXT_MARKDOWN = "text_markdown",
  COST_SUMMARY = "cost_summary",
}

// Widget-specific type definitions with discriminator
export interface ChartMetricWidget {
  type: WIDGET_TYPE.CHART_METRIC;
  config: {
    projectId?: string;
    metricType: string;
    chartType?: "line" | "bar";
    traceFilters?: Filter[];
    threadFilters?: Filter[];
  } & Record<string, unknown>;
}

export interface TextMarkdownWidget {
  type: WIDGET_TYPE.TEXT_MARKDOWN;
  config?: {
    content?: string;
  } & Record<string, unknown>;
}

export interface StatCardWidget {
  type: WIDGET_TYPE.STAT_CARD;
  config: {
    source: "traces" | "spans";
    projectId: string;
    metric: string;
    traceFilters?: Filter[];
    spanFilters?: Filter[];
  } & Record<string, unknown>;
}

export interface CostSummaryWidget {
  type: WIDGET_TYPE.COST_SUMMARY;
  config?: {
    projectIds?: string[];
  } & Record<string, unknown>;
}

// Unified widget config type
export type AddWidgetConfig = {
  title: string;
  subtitle?: string;
} & (
  | ChartMetricWidget
  | TextMarkdownWidget
  | StatCardWidget
  | CostSummaryWidget
);

// Update config with optional fields
export type UpdateWidgetConfig = {
  title?: string;
  subtitle?: string;
} & (
  | {
      type: WIDGET_TYPE.CHART_METRIC;
      config?: Partial<ChartMetricWidget["config"]>;
    }
  | {
      type: WIDGET_TYPE.TEXT_MARKDOWN;
      config?: Partial<NonNullable<TextMarkdownWidget["config"]>>;
    }
  | {
      type: WIDGET_TYPE.STAT_CARD;
      config?: Partial<StatCardWidget["config"]>;
    }
  | {
      type: WIDGET_TYPE.COST_SUMMARY;
      config?: Partial<NonNullable<CostSummaryWidget["config"]>>;
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
  | ChartMetricWidget
  | TextMarkdownWidget
  | StatCardWidget
  | CostSummaryWidget
  | {
      type: string;
      config: Record<string, unknown>;
    }
);

export type WidgetSize = {
  w: number;
  h: number;
};

export interface DashboardLayoutItem {
  i: string;
  x: number;
  y: number;
  w: number;
  h: number;
  minW?: number;
  maxW?: number;
  minH?: number;
  maxH?: number;
}

export type DashboardLayout = DashboardLayoutItem[];

export interface DashboardSection {
  id: string;
  title: string;
  expanded: boolean;
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
  [key: string]: unknown;
}

export interface ProjectDashboardConfig extends BaseDashboardConfig {
  projectId: string;
  dateRange: DateRangeSerializedValue;
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

export interface WidgetComponents {
  Widget: React.ComponentType<DashboardWidgetComponentProps>;
  Editor: WidgetEditorComponent | null;
  getDefaultConfig: () => Record<string, unknown>;
  calculateTitle: (config: Record<string, unknown>) => string;
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

export interface WidgetOption {
  id: string;
  title: string;
  description: string;
  icon: React.ReactNode;
  category: "general" | "charts" | "stats" | "experiments" | "cost";
  disabled?: boolean;
}
