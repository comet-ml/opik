import React, { MouseEventHandler } from "react";
import { Link } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useActiveWorkspaceName, useActiveProjectId } from "@/store/AppStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";

export enum MENU_ITEM_TYPE {
  link = "link",
  router = "router",
  button = "button",
}

export type MenuItem = {
  id: string;
  path?: string;
  type: MENU_ITEM_TYPE;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  disabled?: boolean;
  muted?: boolean;
  exact?: boolean;
  featureFlag?: FeatureToggleKeys;
  onClick?: MouseEventHandler<HTMLButtonElement>;
};

export type MenuItemGroup = {
  id: string;
  label?: string;
  items: MenuItem[];
};

interface SidebarMenuItemProps {
  item: MenuItem;
  expanded: boolean;
}

const SidebarMenuItem: React.FunctionComponent<SidebarMenuItemProps> = ({
  item,
  expanded,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const isFeatureEnabled = useIsFeatureEnabled(item.featureFlag!);

  if (item.featureFlag && !isFeatureEnabled) {
    return null;
  }

  const content = (
    <>
      <item.icon
        className={cn(
          "size-3.5 shrink-0 group-data-[status=active]:text-primary",
          expanded
            ? item.muted
              ? "text-muted-slate"
              : "text-light-slate"
            : item.muted
              ? "text-light-slate"
              : "text-foreground",
        )}
      />
      {expanded && <div className="grow truncate">{item.label}</div>}
    </>
  );

  const linkClasses = cn(
    "comet-body-s group relative flex items-center gap-2 rounded-md hover:bg-primary-foreground data-[status=active]:bg-primary-100 data-[status=active]:text-primary",
    item.muted ? "text-muted-slate" : "text-foreground",
    expanded ? "h-7 w-full px-2 py-1" : "size-6 justify-center",
  );

  if (item.disabled) {
    return (
      <li className="flex">
        <span
          className={cn(linkClasses, "cursor-not-allowed opacity-50")}
          aria-disabled="true"
        >
          {content}
        </span>
      </li>
    );
  }

  let itemElement: React.ReactElement | null = null;

  if (item.type === MENU_ITEM_TYPE.router && item.path) {
    const params: Record<string, string> = { workspaceName };
    if (activeProjectId) {
      params.projectId = activeProjectId;
    }
    itemElement = (
      <li className="flex">
        <Link
          to={item.path}
          params={params}
          activeOptions={{ exact: item.exact ?? false }}
          className={linkClasses}
        >
          {content}
        </Link>
      </li>
    );
  } else if (item.type === MENU_ITEM_TYPE.link && item.path) {
    itemElement = (
      <li className="flex">
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
  } else if (item.type === MENU_ITEM_TYPE.button) {
    itemElement = (
      <li className="flex">
        <button onClick={item.onClick} className={cn(linkClasses, "text-left")}>
          {content}
        </button>
      </li>
    );
  }

  if (expanded || !itemElement) {
    return itemElement;
  }

  return (
    <TooltipWrapper content={item.label} side="right" delayDuration={0}>
      {itemElement}
    </TooltipWrapper>
  );
};

export default SidebarMenuItem;
