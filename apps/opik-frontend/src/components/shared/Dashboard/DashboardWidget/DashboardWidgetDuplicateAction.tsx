import React, { useCallback } from "react";
import { Copy } from "lucide-react";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useDashboardStore, selectAddWidget } from "@/store/DashboardStore";
import { WIDGET_TYPE, AddWidgetConfig } from "@/types/dashboard";

type DashboardWidgetDuplicateActionProps = {
  sectionId: string;
  widgetType: string;
  widgetTitle: string;
  widgetConfig?: Record<string, unknown>;
};

const DashboardWidgetDuplicateAction: React.FC<
  DashboardWidgetDuplicateActionProps
> = ({ sectionId, widgetType, widgetTitle, widgetConfig }) => {
  const addWidget = useDashboardStore(selectAddWidget);

  const handleDuplicate = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      addWidget(sectionId, {
        type: widgetType as WIDGET_TYPE,
        title: widgetTitle,
        config: widgetConfig || {},
      } as AddWidgetConfig);
    },
    [sectionId, widgetType, widgetTitle, widgetConfig, addWidget],
  );

  return (
    <TooltipWrapper content="Duplicate widget">
      <Button
        variant="minimal"
        size="icon-3xs"
        onClick={handleDuplicate}
        className="text-light-slate hover:text-foreground"
      >
        <Copy className="size-4" />
      </Button>
    </TooltipWrapper>
  );
};

export default DashboardWidgetDuplicateAction;
