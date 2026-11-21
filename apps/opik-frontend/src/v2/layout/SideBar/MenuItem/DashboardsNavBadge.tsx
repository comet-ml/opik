import React from "react";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAskOllieForWidget from "@/shared/Dashboard/useAskOllieForWidget";

type DashboardsNavBadgeProps = {
  collapsed: boolean;
};

// Gated by the same check as the Ask Ollie entry in the Add widget menu, so it never advertises an option the
// dashboard won't offer. The collapsed rail has no room for a label, and its tooltip already carries the item name.
const DashboardsNavBadge: React.FC<DashboardsNavBadgeProps> = ({
  collapsed,
}) => {
  const { isOllieAvailable } = useAskOllieForWidget();

  if (!isOllieAvailable || collapsed) return null;

  return (
    <span className="ml-auto flex shrink-0 items-center justify-center pl-1">
      <TooltipWrapper
        content="You can now create custom widgets with Ollie: open a dashboard, click Add widget and choose Ask Ollie."
        side="right"
      >
        <Tag variant="primary" size="sm" className="h-5 px-1.5 leading-[18px]">
          New
        </Tag>
      </TooltipWrapper>
    </span>
  );
};

export default DashboardsNavBadge;
