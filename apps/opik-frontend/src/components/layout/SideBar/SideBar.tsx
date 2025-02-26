import React, { MouseEventHandler, useState } from "react";
import isNumber from "lodash/isNumber";
import { Link, useMatchRoute } from "@tanstack/react-router";

import {
  Book,
  Database,
  FlaskConical,
  GraduationCap,
  LayoutGrid,
  LucideIcon,
  PanelLeftClose,
  MessageCircleQuestion,
  FileTerminal,
  LucideHome,
  Blocks,
  Bolt,
} from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { buildDocsUrl, cn } from "@/lib/utils";
import Logo from "@/components/layout/Logo/Logo";
import usePluginsStore from "@/store/PluginsStore";
import ProvideFeedbackDialog from "@/components/layout/SideBar/FeedbackDialog/ProvideFeedbackDialog";
import usePromptsList from "@/api/prompts/usePromptsList";
import QuickstartDialog from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import GitHubStarListItem from "@/components/layout/SideBar/GitHubStarListItem/GitHubStarListItem";

enum MENU_ITEM_TYPE {
  link = "link",
  router = "router",
  button = "button",
}

type MenuItem = {
  id: string;
  path?: string;
  type: MENU_ITEM_TYPE;
  icon: LucideIcon;
  label: string;
  count?: string;
  onClick?: MouseEventHandler<HTMLButtonElement>;
};

type MenuItemGroup = {
  id: string;
  label?: string;
  items: MenuItem[];
};

const HOME_PATH = "/$workspaceName/home";

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
        id: "datasets",
        path: "/$workspaceName/datasets",
        type: MENU_ITEM_TYPE.router,
        icon: Database,
        label: "Datasets",
        count: "datasets",
      },
      {
        id: "experiments",
        path: "/$workspaceName/experiments",
        type: MENU_ITEM_TYPE.router,
        icon: FlaskConical,
        label: "Experiments",
        count: "experiments",
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
    id: "configuration",
    label: "Configuration",
    items: [
      {
        id: "configuration",
        path: "/$workspaceName/configuration",
        type: MENU_ITEM_TYPE.router,
        icon: Bolt,
        label: "Configuration",
      },
    ],
  },
];

type SideBarProps = {
  expanded: boolean;
  setExpanded: OnChangeFn<boolean | undefined>;
};

interface GetItemElement {
  item: MenuItem;
  content: React.ReactElement;
  workspaceName: string;
  linkClasses: string;
  linkClickHandler: (event: React.MouseEvent<HTMLAnchorElement>) => void;
}

const getItemElementByType = ({
  item,
  content,
  workspaceName,
  linkClasses,
  linkClickHandler,
}: GetItemElement) => {
  if (item.type === MENU_ITEM_TYPE.router) {
    return (
      <li key={item.id} className="flex">
        <Link
          to={item.path}
          params={{ workspaceName }}
          className={linkClasses}
          onClick={linkClickHandler as never}
        >
          {content}
        </Link>
      </li>
    );
  }

  if (item.type === MENU_ITEM_TYPE.link) {
    return (
      <li key={item.id} className="flex">
        <a
          href={item.path}
          target="_blank"
          rel="noreferrer"
          className={linkClasses}
        >
          {content}
        </a>
      </li>
    );
  }

  if (item.type === MENU_ITEM_TYPE.button) {
    return (
      <li key={item.id} className="flex">
        <button onClick={item.onClick} className={cn(linkClasses, "text-left")}>
          {content}
        </button>
      </li>
    );
  }

  return null;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  setExpanded,
}) => {
  const [openProvideFeedback, setOpenProvideFeedback] = useState(false);
  const [openQuickstart, setOpenQuickstart] = useState(false);

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

  const countDataMap: Record<string, number | undefined> = {
    projects: projectData?.total,
    datasets: datasetsData?.total,
    experiments: experimentsData?.total,
    prompts: promptsData?.total,
  };

  const bottomMenuItems: MenuItem[] = [
    {
      id: "documentation",
      path: buildDocsUrl(),
      type: MENU_ITEM_TYPE.link,
      icon: Book,
      label: "Documentation",
    },
    {
      id: "quickstart",
      type: MENU_ITEM_TYPE.button,
      icon: GraduationCap,
      label: "Quickstart guide",
      onClick: () => setOpenQuickstart(true),
    },
    {
      id: "provideFeedback",
      type: MENU_ITEM_TYPE.button,
      icon: MessageCircleQuestion,
      label: "Provide feedback",
      onClick: () => setOpenProvideFeedback(true),
    },
  ];

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

  const renderItems = (items: MenuItem[]) => {
    return items.map((item) => {
      const hasCount = item.count && isNumber(countDataMap[item.count]);
      const count = hasCount ? countDataMap[item.count!] : "";

      const content = (
        <>
          <item.icon className="size-4 shrink-0" />
          {expanded && (
            <>
              <div className="ml-1 grow truncate">{item.label}</div>
              {hasCount && (
                <div className="h-6 shrink-0 leading-6">{count}</div>
              )}
            </>
          )}
        </>
      );

      const linkClasses = cn(
        "comet-body-s flex h-9 w-full items-center gap-2 text-foreground rounded-md hover:bg-primary-foreground data-[status=active]:bg-primary-100 data-[status=active]:text-primary",
        expanded ? "pl-[10px] pr-3" : "w-9 justify-center",
      );

      const itemElement = getItemElementByType({
        item,
        content,
        workspaceName,
        linkClasses,
        linkClickHandler,
      });

      if (expanded) {
        return itemElement;
      }

      return (
        <TooltipWrapper key={item.id} content={item.label} side="right">
          {itemElement}
        </TooltipWrapper>
      );
    });
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

  return (
    <>
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
              <PanelLeftClose className="size-4" />
            </Button>
          )}
        </div>
        <div className="flex h-[calc(100%-var(--header-height))] flex-col justify-between px-3 py-4">
          <ul className="flex flex-col gap-1">{renderGroups(MENU_ITEMS)}</ul>
          <div className="flex flex-col gap-4">
            <Separator />
            <ul className="flex flex-col gap-1">
              <GitHubStarListItem expanded={expanded} />
              {renderItems(bottomMenuItems)}
            </ul>
          </div>
        </div>
      </aside>

      <ProvideFeedbackDialog
        open={openProvideFeedback}
        setOpen={setOpenProvideFeedback}
      />

      <QuickstartDialog open={openQuickstart} setOpen={setOpenQuickstart} />
    </>
  );
};

export default SideBar;
