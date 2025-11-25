import React from "react";
import { ArrowRightLeft } from "lucide-react";
import { useShallow } from "zustand/react/shallow";

import {
  useDashboardStore,
  selectSections,
  selectMoveWidget,
} from "@/store/DashboardStore";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DashboardWidgetMoveActionProps = {
  sectionId: string;
  widgetId: string;
};

const DashboardWidgetMoveAction: React.FC<DashboardWidgetMoveActionProps> = ({
  sectionId,
  widgetId,
}) => {
  const sections = useDashboardStore(useShallow(selectSections));
  const moveWidget = useDashboardStore(selectMoveWidget);

  const availableSections = sections.filter((s) => s.id !== sectionId);

  const handleMoveToSection = (targetSectionId: string) => {
    moveWidget(sectionId, targetSectionId, widgetId);
  };

  if (availableSections.length === 0) {
    return null;
  }

  return (
    <DropdownMenu>
      <TooltipWrapper content="Move to section">
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon-3xs">
            <ArrowRightLeft className="size-4" />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent align="end" className="w-52">
        {availableSections.map((section) => (
          <DropdownMenuItem
            key={section.id}
            onClick={() => handleMoveToSection(section.id)}
          >
            {section.title}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DashboardWidgetMoveAction;
