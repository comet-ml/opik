import React from "react";
import isNumber from "lodash/isNumber";
import { Link, useMatchRoute } from "@tanstack/react-router";
import {
  Database,
  FlaskConical,
  LayoutGrid,
  MessageSquare,
  PanelRightOpen,
} from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import Logo from "@/components/layout/Logo/Logo";
import usePluginsStore from "@/store/PluginsStore";

const ITEMS = [
  {
    path: "/$workspaceName/projects",
    icon: LayoutGrid,
    label: "Projects",
    count: "projects",
  },
  {
    path: "/$workspaceName/datasets",
    icon: Database,
    label: "Datasets",
    count: "datasets",
  },
  {
    path: "/$workspaceName/experiments",
    icon: FlaskConical,
    label: "Experiments",
    count: "experiments",
  },
  {
    path: "/$workspaceName/feedback-definitions",
    icon: MessageSquare,
    label: "Feedback definitions",
    count: "feedbackDefinitions",
  },
];

type SideBarProps = {
  expanded: boolean;
  setExpanded: OnChangeFn<boolean | undefined>;
};

const HOME_PATH = "/$workspaceName/projects";

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  setExpanded,
}) => {
  const matchRoute = useMatchRoute();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const LogoComponent = usePluginsStore((state) => state.Logo);

  const isHomePath = matchRoute({
    to: HOME_PATH,
    fuzzy: true,
  });

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
  const { data: feedbackDefinitions } = useFeedbackDefinitionsList(
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
    feedbackDefinitions: feedbackDefinitions?.total,
  };

  const linkClickHandler = (event: React.MouseEvent<HTMLAnchorElement>) => {
    const target = event.currentTarget;
    const isActive = target.getAttribute("data-status") === "active";
    if (isActive) {
      setExpanded(true);
    }
  };

  const logoClickHandler = () => {
    if (isHomePath) {
      setExpanded((state) => !state);
    }
  };

  const logo = LogoComponent ? (
    <LogoComponent expanded={expanded} />
  ) : (
    <Logo expanded={expanded} />
  );

  const renderItems = () => {
    return ITEMS.map((item) => {
      const hasCount = item.count && isNumber(countDataMap[item.count]);
      const count = hasCount ? countDataMap[item.count] : "";

      const itemElement = (
        <li key={item.path} className="flex">
          <Link
            to={item.path}
            params={{ workspaceName }}
            className={cn(
              "comet-body-s flex h-9 w-full items-center gap-2 text-foreground rounded-md hover:bg-primary-foreground data-[status=active]:bg-primary-100 data-[status=active]:text-primary",
              expanded ? "pl-[10px] pr-3" : "w-9 justify-center",
            )}
            onClick={linkClickHandler as never}
          >
            <item.icon className="size-4 shrink-0" />
            {expanded && (
              <>
                <div className="ml-1 grow truncate">{item.label}</div>
                {hasCount && (
                  <div className="h-6 shrink-0 leading-6">{count}</div>
                )}
              </>
            )}
          </Link>
        </li>
      );

      if (expanded) {
        return itemElement;
      }
      return (
        <TooltipWrapper key={item.path} content={item.label} side="right">
          {itemElement}
        </TooltipWrapper>
      );
    });
  };

  return (
    <aside className="comet-sidebar-width h-full border-r transition-all">
      <div className="comet-header-height relative flex w-full items-center justify-between gap-6 border-b">
        <Link
          to={HOME_PATH}
          className="absolute left-[18px] z-10 block"
          params={{ workspaceName }}
          onClick={logoClickHandler}
        >
          {logo}
        </Link>
        {expanded && (
          <Button
            className="absolute right-2.5"
            size="icon"
            variant="minimal"
            onClick={() => setExpanded(false)}
          >
            <PanelRightOpen className="size-4" />
          </Button>
        )}
      </div>
      <div className="flex h-full flex-col justify-between px-3 py-6">
        <ul className="flex flex-col gap-2">{renderItems()}</ul>
      </div>
    </aside>
  );
};

export default SideBar;
