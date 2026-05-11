import {
  Bell,
  Blocks,
  Bot,
  ChartLine,
  Database,
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
import OllieOwl from "@/icons/ollie-owl.svg?react";
import {
  MENU_ITEM_TYPE,
  MenuItemGroup,
} from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";

const getMenuItems = ({
  projectId,
  canViewExperiments,
  canViewDatasets,
  canViewDashboards,
  canUsePlayground,
  canViewOptimizationRuns,
  showOlliePage,
}: {
  projectId: string | null;
  canViewExperiments: boolean;
  canViewDatasets: boolean;
  canViewDashboards: boolean;
  canUsePlayground: boolean;
  canViewOptimizationRuns: boolean;
  showOlliePage: boolean;
}): MenuItemGroup[] => {
  const projectPrefix = projectId
    ? "/$workspaceName/projects/$projectId"
    : null;

  const projectPath = (suffix: string) =>
    projectPrefix ? `${projectPrefix}${suffix}` : undefined;

  return [
    // TODO: OPIK-6260 - Restore Home page item and separate Ollie route once home page redesign is complete
    ...(showOlliePage
      ? [
          {
            id: "home_group",
            items: [
              {
                id: "ollie",
                path: projectPath("/home"),
                type: MENU_ITEM_TYPE.router as const,
                icon: OllieOwl,
                label: "Opik Connect",
                disabled: !projectPrefix,
              },
            ],
          },
        ]
      : []),
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
        ...(canViewDashboards
          ? [
              {
                id: "dashboards",
                path: projectPath("/dashboards"),
                type: MENU_ITEM_TYPE.router as const,
                icon: ChartLine,
                label: "Dashboards",
                disabled: !projectPrefix,
              },
            ]
          : []),
      ],
    },
    {
      id: "development",
      label: "Development",
      items: [
        {
          id: "agent_runner",
          path: projectPath("/agent-playground"),
          type: MENU_ITEM_TYPE.router,
          icon: GitBranch,
          label: "Agent playground",
          disabled: !projectPrefix,
        },
        ...(canUsePlayground
          ? [
              {
                id: "playground",
                path: projectPath("/playground"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Blocks,
                label: "Prompt playground",
                disabled: !projectPrefix,
              },
            ]
          : []),
        {
          id: "agent_configuration",
          path: projectPath("/agent-configuration"),
          type: MENU_ITEM_TYPE.router,
          icon: Workflow,
          label: "Agent configuration",
          disabled: !projectPrefix,
        },
        ...(canViewOptimizationRuns
          ? [
              {
                id: "optimizations",
                path: projectPath("/optimizations"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Sparkles,
                label: "Optimization runs",
                disabled: !projectPrefix,
              },
            ]
          : []),
      ],
    },
    {
      id: "evaluation",
      label: "Evaluation",
      items: [
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
              {
                id: "datasets",
                path: projectPath("/datasets"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Database,
                label: "Datasets",
                disabled: !projectPrefix,
              },
            ]
          : []),
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
        },
      ],
    },
  ];
};

export const getWorkspaceMenuItems = (): MenuItemGroup[] => {
  return [
    {
      id: "workspace-nav",
      items: [
        {
          id: "workspace",
          path: "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: LayoutDashboard,
          label: "Workspace",
          muted: true,
          exact: true,
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
