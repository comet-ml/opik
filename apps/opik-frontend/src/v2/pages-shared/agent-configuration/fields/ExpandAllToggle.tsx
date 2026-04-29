import React from "react";
import { FoldVertical, UnfoldVertical } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { FieldsCollapseController } from "./useFieldsCollapse";

type ExpandAllToggleProps = {
  controller: FieldsCollapseController;
  size?: "icon-xs" | "icon-2xs" | "icon-sm";
};

const ExpandAllToggle: React.FC<ExpandAllToggleProps> = ({
  controller,
  size = "icon-2xs",
}) => {
  const label = controller.allExpanded ? "Collapse all" : "Expand all";
  const onClick = () =>
    controller.allExpanded ? controller.collapseAll() : controller.expandAll();
  const Icon = controller.allExpanded ? FoldVertical : UnfoldVertical;

  return (
    <TooltipWrapper content={label}>
      <Button
        variant="outline"
        size={size}
        onClick={onClick}
        aria-label={label}
      >
        <Icon />
      </Button>
    </TooltipWrapper>
  );
};

export default ExpandAllToggle;
