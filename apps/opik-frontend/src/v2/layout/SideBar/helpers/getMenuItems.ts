import {
  Bell,
  Blocks,
  ChartLine,
  FileTerminal,
  FlaskConical,
  ListChecks,
  Rows3,
  Settings2,
  Sparkles,
  UserPen,
  Brain,
  Workflow,
} from "lucide-react";
import {
  MENU_ITEM_TYPE,
  MenuItemGroup,
} from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const getMenuItems = ({
  projectId,
  canViewExperiments,
  canViewDatasets,
}: {
  projectId: string | null;
  canViewExperiments: boolean;
  canViewDatasets: boolean;
}): MenuItemGroup[] => {
  const projectPrefix = projectId
    ? "/$workspaceName/projects/$projectId"
    : null;

  const projectPath = (suffix: string) =>
    projectPrefix ? `${projectPrefix}${suffix}` : undefined;

  return [
    {
      id: "observability",
      label: "Observability",
      items: [
        {
          id: "logs",
          path: projectPath("/traces"),
          type: MENU_ITEM_TYPE.router,
          icon: Rows3,
          label: "Logs",
          disabled: !projectPrefix,
        },
        {
          id: "insights",
          type: MENU_ITEM_TYPE.router,
          icon: ChartLine,
          label: "Insights",
          disabled: true,
        },
      ],
    },
    {
      id: "evaluation",
      label: "Evaluation",
      items: [
        ...(canViewExperiments
          ? [
              {
                id: "experiments",
                path: projectPath("/experiments"),
                type: MENU_ITEM_TYPE.router as const,
                icon: FlaskConical,
                label: "Experiments",
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(canViewDatasets
          ? [
              {
                id: "evaluation_suites",
                path: projectPath("/evaluation-suites"),
                type: MENU_ITEM_TYPE.router as const,
                icon: ListChecks,
                label: "Evaluation suites",
                disabled: !projectPrefix,
              },
            ]
          : []),
        {
          id: "annotation_queues",
          path: projectPath("/annotation-queues"),
          type: MENU_ITEM_TYPE.router,
          icon: UserPen,
          label: "Annotation queues",
          disabled: !projectPrefix,
        },
      ],
    },
    {
      id: "prompt_engineering",
      label: "Prompt engineering",
      items: [
        {
          id: "prompts",
          path: projectPath("/prompts"),
          type: MENU_ITEM_TYPE.router,
          icon: FileTerminal,
          label: "Prompt library",
          disabled: !projectPrefix,
        },
        {
          id: "playground",
          path: projectPath("/playground"),
          type: MENU_ITEM_TYPE.router,
          icon: Blocks,
          label: "Playground",
          disabled: !projectPrefix,
        },
      ],
    },
    {
      id: "optimization",
      label: "Optimization",
      items: [
        {
          id: "optimizations",
          path: projectPath("/optimizations"),
          type: MENU_ITEM_TYPE.router,
          icon: Sparkles,
          label: "Optimization studio",
          disabled: !projectPrefix,
        },
        {
          id: "agent_configuration",
          path: projectPath("/agent-configuration"),
          type: MENU_ITEM_TYPE.router,
          icon: Workflow,
          label: "Agent configuration",
          disabled: !projectPrefix,
        },
      ],
    },
    {
      id: "production",
      label: "Production",
      items: [
        {
          id: "online_evaluation",
          path: projectPath("/online-evaluation"),
          type: MENU_ITEM_TYPE.router,
          icon: Brain,
          label: "Online evaluation",
          disabled: !projectPrefix,
        },
        {
          id: "alerts",
          path: projectPath("/alerts"),
          type: MENU_ITEM_TYPE.router,
          icon: Bell,
          label: "Alerts",
          disabled: !projectPrefix,
          featureFlag: FeatureToggleKeys.TOGGLE_ALERTS_ENABLED,
        },
      ],
    },
  ];
};

export const getWorkspaceMenuItems = ({
  canViewDashboards,
}: {
  canViewDashboards: boolean;
}): MenuItemGroup[] => {
  return [
    {
      id: "workspace",
      label: "Workspace",
      items: [
        ...(canViewDashboards
          ? [
              {
                id: "dashboards",
                path: "/$workspaceName/dashboards",
                type: MENU_ITEM_TYPE.router as const,
                icon: ChartLine,
                label: "Dashboards",
              },
            ]
          : []),
        {
          id: "configuration",
          path: "/$workspaceName/configuration",
          type: MENU_ITEM_TYPE.router,
          icon: Settings2,
          label: "Configuration",
        },
      ],
    },
  ];
};

export default getMenuItems;
