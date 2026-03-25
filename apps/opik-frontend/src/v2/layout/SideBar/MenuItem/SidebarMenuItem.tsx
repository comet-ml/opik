import React, { MouseEventHandler } from "react";
import { Link } from "@tanstack/react-router";
import { LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";
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
  icon: LucideIcon;
  label: string;
  disabled?: boolean;
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
}

const SidebarMenuItem: React.FunctionComponent<SidebarMenuItemProps> = ({
  item,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const isFeatureEnabled = useIsFeatureEnabled(item.featureFlag!);

  if (item.featureFlag && !isFeatureEnabled) {
    return null;
  }

  const content = (
    <>
      <item.icon className="size-3.5 shrink-0" />
      <div className="grow truncate">{item.label}</div>
    </>
  );

  const baseClasses =
    "comet-body-s relative flex w-full items-center gap-2 rounded-md px-2 py-1";

  if (item.disabled) {
    return (
      <li className="flex">
        <span
          className={cn(baseClasses, "cursor-not-allowed opacity-50")}
          aria-disabled="true"
        >
          {content}
        </span>
      </li>
    );
  }

  const linkClasses = cn(
    baseClasses,
    "hover:bg-primary-foreground data-[status=active]:bg-muted data-[status=active]:font-medium",
  );

  let itemElement: React.ReactElement | null = null;

  if (item.type === MENU_ITEM_TYPE.router && item.path) {
    const params: Record<string, string> = { workspaceName };
    if (activeProjectId) {
      params.projectId = activeProjectId;
    }
    itemElement = (
      <li className="flex">
        <Link to={item.path} params={params} className={linkClasses}>
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

  return itemElement;
};

export default SidebarMenuItem;
