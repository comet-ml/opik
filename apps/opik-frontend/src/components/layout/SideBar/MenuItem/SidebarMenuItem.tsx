import React, { MouseEventHandler } from "react";
import isNumber from "lodash/isNumber";
import { Link } from "@tanstack/react-router";
import { LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { ENTITY_TYPE, ENTITY_COLORS } from "@/constants/colors";

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
  featureFlag?: FeatureToggleKeys;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  entityType?: ENTITY_TYPE;
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
}) => {
  const { activeWorkspaceName: workspaceName } = useAppStore();
  const hasCount = item.count && isNumber(count);
  const isFeatureEnabled = useIsFeatureEnabled(item.featureFlag!);

  if (item.featureFlag && !isFeatureEnabled) {
    return null;
  }

  const iconColor =
    item.entityType !== undefined ? ENTITY_COLORS[item.entityType] : undefined;

  const content = (
    <>
      <item.icon
        className="size-4 shrink-0"
        style={
          iconColor ? ({ color: iconColor } as React.CSSProperties) : undefined
        }
      />
      {expanded && (
        <>
          <div className="ml-1 grow truncate">{item.label}</div>
          {hasCount && <div className="h-6 shrink-0 leading-6">{count}</div>}
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
