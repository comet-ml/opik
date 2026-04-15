import {
  Bell,
  Blocks,
  Bot,
  ChartLine,
  FileTerminal,
  FlaskConical,
  LayoutDashboard,
  ListChecks,
  Rows3,
  Settings2,
  Sparkles,
  UserPen,
  Brain,
  GitBranch,
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
  canUsePlayground,
}: {
  projectId: string | null;
  canViewExperiments: boolean;
  canViewDatasets: boolean;
  canUsePlayground: boolean;
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
          path: projectPath("/logs"),
          type: MENU_ITEM_TYPE.router,
          icon: Rows3,
          label: "Logs",
          disabled: !projectPrefix,
        },
        {
          id: "dashboards",
          path: projectPath("/dashboards"),
          type: MENU_ITEM_TYPE.router,
          icon: ChartLine,
          label: "Dashboards",
          disabled: !projectPrefix,
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
                id: "test_suites",
                path: projectPath("/test-suites"),
                type: MENU_ITEM_TYPE.router as const,
                icon: ListChecks,
                label: "Test suites",
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
        ...(canUsePlayground
          ? [
              {
                id: "playground",
                path: projectPath("/playground"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Blocks,
                label: "Playground",
                disabled: !projectPrefix,
              },
            ]
          : []),
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
        {
          id: "agent_runner",
          path: projectPath("/agent-runner"),
          type: MENU_ITEM_TYPE.router,
          icon: GitBranch,
          label: "Agent sandbox",
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
      id: "workspace-nav",
      items: [
        {
          id: "workspace",
          path: canViewDashboards
            ? "/$workspaceName/dashboards"
            : "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: LayoutDashboard,
          label: "Workspace",
          muted: true,
        },
        {
          id: "configuration",
          path: "/$workspaceName/configuration",
          type: MENU_ITEM_TYPE.router,
          icon: Settings2,
          label: "Configuration",
          muted: true,
        },
      ],
    },
  ];
};

export const getWorkspaceSidebarMenuItems = ({
  canViewDashboards,
}: {
  canViewDashboards: boolean;
}): MenuItemGroup[] => {
  return [
    {
      id: "workspace-sidebar",
      items: [
        {
          id: "configuration",
          path: "/$workspaceName/configuration",
          type: MENU_ITEM_TYPE.router,
          icon: Settings2,
          label: "Configuration",
        },
        {
          id: "projects",
          path: "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: Bot,
          label: "Projects",
        },
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
      ],
    },
  ];
};

export default getMenuItems;
