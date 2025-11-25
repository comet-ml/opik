import React from "react";
import { GripVertical } from "lucide-react";

import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const DashboardWidgetDragHandle: React.FC = () => {
  return (
    <TooltipWrapper content="Drag to reposition">
      {/* eslint-disable-next-line tailwindcss/no-custom-classname */}
      <div className="drag-handle cursor-grab text-light-slate hover:text-foreground active:cursor-grabbing">
        <GripVertical className="size-4" />
      </div>
    </TooltipWrapper>
  );
};

export default DashboardWidgetDragHandle;
