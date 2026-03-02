import {
  Bell,
  FlaskConical,
  LayoutGrid,
  FileTerminal,
  ListChecks,
  LucideHome,
  Blocks,
  Brain,
  ChartLine,
  SparklesIcon,
  UserPen,
} from "lucide-react";
import {
  MENU_ITEM_TYPE,
  MenuItemGroup,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const getMenuItems = ({
  canViewExperiments,
  canViewDashboards,
  canViewDatasets,
}: {
  canViewExperiments: boolean;
  canViewDashboards: boolean;
  canViewDatasets: boolean;
}): MenuItemGroup[] => {
  return [
    {
      id: "home",
      items: [
        {
          id: "home",
          path: "/$workspaceName/home",
          type: MENU_ITEM_TYPE.router,
          icon: LucideHome,
          label: "Home",
        },
        ...(canViewDashboards
          ? [
              {
                id: "dashboards",
                path: "/$workspaceName/dashboards",
                type: MENU_ITEM_TYPE.router,
                icon: ChartLine,
                label: "Dashboards",
                count: "dashboards",
              },
            ]
          : []),
      ],
    },
    {
      id: "observability",
      label: "Observability",
      items: [
        {
          id: "projects",
          path: "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: LayoutGrid,
          label: "Projects",
          count: "projects",
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
                id: "experiments" as const,
                path: "/$workspaceName/experiments" as const,
                type: MENU_ITEM_TYPE.router,
                icon: FlaskConical,
                label: "Experiments" as const,
                count: "experiments" as const,
              },
            ]
          : []),
        ...(canViewDatasets
          ? [
              {
                id: "evaluation_suites",
                path: "/$workspaceName/evaluation-suites",
                type: MENU_ITEM_TYPE.router,
                icon: ListChecks,
                label: "Evaluation suites",
                count: "evaluation_suites",
              },
            ]
          : []),
        {
          id: "annotation_queues",
          path: "/$workspaceName/annotation-queues",
          type: MENU_ITEM_TYPE.router,
          icon: UserPen,
          label: "Annotation queues",
          count: "annotation_queues",
        },
      ],
    },
    {
      id: "prompt_engineering",
      label: "Prompt engineering",
      items: [
        {
          id: "prompts",
          path: "/$workspaceName/prompts",
          type: MENU_ITEM_TYPE.router,
          icon: FileTerminal,
          label: "Prompt library",
          count: "prompts",
        },
        {
          id: "playground",
          path: "/$workspaceName/playground",
          type: MENU_ITEM_TYPE.router,
          icon: Blocks,
          label: "Playground",
        },
      ],
    },
    {
      id: "optimization",
      label: "Optimization",
      items: [
        {
          id: "optimizations",
          path: "/$workspaceName/optimizations",
          type: MENU_ITEM_TYPE.router,
          icon: SparklesIcon,
          label: "Optimization studio",
          count: "optimizations",
          showIndicator: "optimizations_running",
        },
      ],
    },
    {
      id: "production",
      label: "Production",
      items: [
        {
          id: "online_evaluation",
          path: "/$workspaceName/online-evaluation",
          type: MENU_ITEM_TYPE.router,
          icon: Brain,
          label: "Online evaluation",
          count: "rules",
        },
        {
          id: "alerts",
          path: "/$workspaceName/alerts",
          type: MENU_ITEM_TYPE.router,
          icon: Bell,
          label: "Alerts",
          count: "alerts",
          featureFlag: FeatureToggleKeys.TOGGLE_ALERTS_ENABLED,
        },
      ],
    },
  ];
};

export default getMenuItems;
