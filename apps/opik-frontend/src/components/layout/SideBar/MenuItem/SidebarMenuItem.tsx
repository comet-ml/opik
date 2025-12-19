import React, { MouseEventHandler } from "react";
import isNumber from "lodash/isNumber";
import { Link } from "@tanstack/react-router";
import { LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";

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
  count?: string;
  showIndicator?: string;
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
  count?: number;
  hasIndicator?: boolean;
  compact?: boolean;
}

interface GetItemElementProps {
  item: MenuItem;
  content: React.ReactElement;
  workspaceName: string;
  linkClasses: string;
}

const getItemElementByType = ({
  item,
  content,
  workspaceName,
  linkClasses,
}: GetItemElementProps): React.ReactElement | null => {
  if (item.type === MENU_ITEM_TYPE.router) {
    return (
      <li key={item.id} className="flex">
        <Link to={item.path} params={{ workspaceName }} className={linkClasses}>
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

const SidebarMenuItem: React.FunctionComponent<SidebarMenuItemProps> = ({
  item,
  expanded,
  count,
  hasIndicator,
  compact = false,
}) => {
  const { activeWorkspaceName: workspaceName } = useAppStore();
  const hasCount = item.count && isNumber(count);
  const showIndicatorBadge = item.showIndicator && hasIndicator;
  const isFeatureEnabled = useIsFeatureEnabled(item.featureFlag!);

  if (item.featureFlag && !isFeatureEnabled) {
    return null;
  }

  const content = (
    <>
      <item.icon className="size-4 shrink-0" />
      {expanded && (
        <>
          <div className="grow truncate">{item.label}</div>
          {showIndicatorBadge ? (
            <div className="size-2 shrink-0 rounded-full bg-[var(--color-green)]" />
          ) : (
            hasCount && <div className="h-6 shrink-0 leading-6">{count}</div>
          )}
        </>
      )}
      {!expanded && showIndicatorBadge && (
        <div className="absolute right-1 top-1 size-2 rounded-full bg-[var(--color-green)]" />
      )}
    </>
  );

  const linkClasses = cn(
    "comet-body-s relative flex w-full items-center gap-2 rounded-md hover:bg-primary-foreground data-[status=active]:bg-primary-100 data-[status=active]:text-primary",
    compact
      ? "h-8 text-muted-slate data-[status=active]:text-muted-slate data-[status=active]:bg-muted"
      : "h-9 text-foreground",
    compact
      ? expanded
        ? "px-2.5"
        : "w-8 justify-center"
      : expanded
        ? "pl-[10px] pr-3"
        : "w-9 justify-center",
  );

  const itemElement = getItemElementByType({
    item,
    content,
    workspaceName,
    linkClasses,
  });

  if (expanded || !itemElement) {
    return itemElement;
  }

  return (
    <TooltipWrapper content={item.label} side="right">
      {itemElement}
    </TooltipWrapper>
  );
};

export default SidebarMenuItem;
