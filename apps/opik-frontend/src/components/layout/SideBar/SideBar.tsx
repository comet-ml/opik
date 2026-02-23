import React, { useState } from "react";
import { Link } from "@tanstack/react-router";

import {
  Bell,
  Database,
  FlaskConical,
  LayoutGrid,
  FileTerminal,
  LucideHome,
  Blocks,
  Bolt,
  Brain,
  ChevronLeft,
  ChevronRight,
  SparklesIcon,
  UserPen,
  ChartLine,
  ScrollText,
} from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useRulesList from "@/api/automations/useRulesList";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import useAlertsList from "@/api/alerts/useAlertsList";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import Logo from "@/components/layout/Logo/Logo";
import usePluginsStore from "@/store/PluginsStore";
import ProvideFeedbackDialog from "@/components/layout/SideBar/FeedbackDialog/ProvideFeedbackDialog";
import usePromptsList from "@/api/prompts/usePromptsList";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import GitHubStarListItem from "@/components/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import SupportHubDropdown from "@/components/layout/SideBar/SupportHubDropdown/SupportHubDropdown";
import SidebarMenuItem, {
  MENU_ITEM_TYPE,
  MenuItem,
  MenuItemGroup,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { ACTIVE_OPTIMIZATION_FILTER } from "@/lib/optimizations";

const HOME_PATH = "/$workspaceName/home";

const RUNNING_OPTIMIZATION_REFETCH_INTERVAL = 5000;

const CONFIGURATION_ITEM: MenuItem = {
  id: "configuration",
  path: "/$workspaceName/configuration",
  type: MENU_ITEM_TYPE.router,
  icon: Bolt,
  label: "Configuration",
};

const MENU_ITEMS: MenuItemGroup[] = [
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
      {
        id: "dashboards",
        path: "/$workspaceName/dashboards",
        type: MENU_ITEM_TYPE.router,
        icon: ChartLine,
        label: "Dashboards",
        count: "dashboards",
      },
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
      {
        id: "experiments",
        path: "/$workspaceName/experiments",
        type: MENU_ITEM_TYPE.router,
        icon: FlaskConical,
        label: "Experiments",
        count: "experiments",
      },
      {
        id: "datasets",
        path: "/$workspaceName/datasets",
        type: MENU_ITEM_TYPE.router,
        icon: Database,
        label: "Datasets",
        count: "datasets",
      },
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
        id: "rule_generator",
        path: "/$workspaceName/rule-generator",
        type: MENU_ITEM_TYPE.router,
        icon: ScrollText,
        label: "Rule generator",
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

type SideBarProps = {
  expanded: boolean;
  setExpanded: OnChangeFn<boolean | undefined>;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  setExpanded,
}) => {
  const [openProvideFeedback, setOpenProvideFeedback] = useState(false);
  const { open: openQuickstart } = useOpenQuickStartDialog();

  const { activeWorkspaceName: workspaceName } = useAppStore();
  const LogoComponent = usePluginsStore((state) => state.Logo);
  const SidebarInviteDevButton = usePluginsStore(
    (state) => state.SidebarInviteDevButton,
  );

  const { data: projectData } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: datasetsData } = useDatasetsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: experimentsData } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: promptsData } = usePromptsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: optimizationsData } = useOptimizationsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: runningOptimizationsData } = useOptimizationsList(
    {
      workspaceName,
      filters: ACTIVE_OPTIMIZATION_FILTER,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: RUNNING_OPTIMIZATION_REFETCH_INTERVAL,
      enabled: !!workspaceName,
    },
  );

  const { data: annotationQueuesData } = useAnnotationQueuesList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: alertsData } = useAlertsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: dashboardsData } = useDashboardsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const countDataMap: Record<string, number | undefined> = {
    projects: projectData?.total,
    datasets: datasetsData?.total,
    experiments: experimentsData?.total,
    prompts: promptsData?.total,
    rules: rulesData?.total,
    optimizations: optimizationsData?.total,
    annotation_queues: annotationQueuesData?.total,
    alerts: alertsData?.total,
    dashboards: dashboardsData?.total,
  };

  const hasActiveOptimizations = (runningOptimizationsData?.total ?? 0) > 0;

  const indicatorDataMap: Record<string, boolean> = {
    optimizations_running: hasActiveOptimizations,
  };

  const logo = LogoComponent ? (
    <LogoComponent expanded={expanded} />
  ) : (
    <Logo expanded={expanded} />
  );

  const renderItems = (items: MenuItem[]) => {
    return items.map((item) => (
      <SidebarMenuItem
        key={item.id}
        item={item}
        expanded={expanded}
        count={countDataMap[item.count!]}
        hasIndicator={indicatorDataMap[item.showIndicator!]}
      />
    ));
  };

  const renderBottomItems = () => {
    const bottomItems = [
      <SidebarMenuItem
        key="configuration"
        item={CONFIGURATION_ITEM}
        expanded={expanded}
        compact
      />,
      <SupportHubDropdown
        key="support-hub"
        expanded={expanded}
        openQuickstart={openQuickstart}
        openProvideFeedback={() => setOpenProvideFeedback(true)}
      />,
    ];

    if (SidebarInviteDevButton) {
      bottomItems.push(
        <SidebarInviteDevButton key="inviteDevButton" expanded={expanded} />,
      );
    }

    return bottomItems;
  };

  const renderGroups = (groups: MenuItemGroup[]) => {
    return groups.map((group) => {
      return (
        <li key={group.id} className={cn(expanded && "mb-1")}>
          <div>
            {group.label && expanded && (
              <div className="comet-body-s truncate pb-1 pl-2.5 pr-3 pt-3 text-light-slate">
                {group.label}
              </div>
            )}

            <ul>{renderItems(group.items)}</ul>
          </div>
        </li>
      );
    });
  };

  const renderExpandCollapseButton = () => {
    return (
      <Button
        variant="outline"
        size="icon-2xs"
        onClick={() => setExpanded((s) => !s)}
        className={cn(
          "absolute -right-3 top-2 hidden rounded-full z-50 lg:group-hover:flex",
        )}
      >
        {expanded ? <ChevronLeft /> : <ChevronRight />}
      </Button>
    );
  };

  return (
    <>
      <aside className="comet-sidebar-width group h-[calc(100vh-var(--banner-height))] border-r transition-all">
        <div className="comet-header-height relative flex w-full items-center justify-between gap-6 border-b">
          <Link
            to={HOME_PATH}
            className="absolute left-[18px] z-10 block"
            params={{ workspaceName }}
          >
            {logo}
          </Link>
        </div>
        <div className="relative flex h-[calc(100%-var(--header-height))]">
          {renderExpandCollapseButton()}
          <div className="flex min-h-0 grow flex-col justify-between overflow-auto px-3 py-4">
            <ul className="flex flex-col gap-1 pb-2">
              {renderGroups(MENU_ITEMS)}
            </ul>
            <div className="flex flex-col gap-3">
              <Separator />
              <ul className="flex flex-col">
                <GitHubStarListItem expanded={expanded} />
                {renderBottomItems()}
              </ul>
            </div>
          </div>
        </div>
      </aside>

      <ProvideFeedbackDialog
        open={openProvideFeedback}
        setOpen={setOpenProvideFeedback}
      />
    </>
  );
};

export default SideBar;
