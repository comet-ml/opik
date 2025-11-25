import React, { useCallback } from "react";
import { Pencil } from "lucide-react";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const DashboardWidgetEditAction: React.FC = () => {
  const handleEdit = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    // TODO: Implement edit widget dialog (Phase 1.5)
  }, []);

  return (
    <TooltipWrapper content="Edit widget">
      <Button
        variant="minimal"
        size="icon-3xs"
        onClick={handleEdit}
        className="text-light-slate hover:text-foreground"
      >
        <Pencil className="size-4" />
      </Button>
    </TooltipWrapper>
  );
};

export default DashboardWidgetEditAction;
