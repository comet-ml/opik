import React from "react";
import {
  Pencil,
  Trash,
  Copy,
  RotateCcw,
  SquareArrowOutUpRight,
  Download,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

export type ActionButtonType =
  | "edit"
  | "delete"
  | "duplicate"
  | "restore"
  | "external"
  | "download";

export interface ActionButtonConfig {
  type: ActionButtonType;
  label?: string;
  showLabel?: boolean;
  onClick: () => void;
  disabled?: boolean;
}

interface RowActionsButtonsProps {
  actions: ActionButtonConfig[];
}

const ACTION_ICONS: Record<ActionButtonType, React.ElementType> = {
  edit: Pencil,
  delete: Trash,
  duplicate: Copy,
  restore: RotateCcw,
  external: SquareArrowOutUpRight,
  download: Download,
};

const DEFAULT_LABELS: Record<ActionButtonType, string> = {
  edit: "Edit",
  delete: "Delete",
  duplicate: "Duplicate",
  restore: "Restore",
  external: "Open in new tab",
  download: "Download",
};

export const RowActionsButtons: React.FC<RowActionsButtonsProps> = ({
  actions,
}) => {
  return (
    <ButtonGroup>
      {actions.map((action, index) => {
        const Icon = ACTION_ICONS[action.type];
        const label = action.label ?? DEFAULT_LABELS[action.type];
        const withLabel = Boolean(action.showLabel);

        const button = (
          <Button
            variant="outline"
            size={withLabel ? "xs" : "icon-xs"}
            onClick={action.onClick}
            disabled={action.disabled}
            aria-label={label}
            className={cn(
              withLabel && "gap-1.5 [&>svg]:size-3.5 [&>svg]:shrink-0",
            )}
          >
            <Icon />
            {withLabel ? label : null}
          </Button>
        );

        return withLabel ? (
          <React.Fragment key={`${action.type}-${index}`}>
            {button}
          </React.Fragment>
        ) : (
          <TooltipWrapper key={`${action.type}-${index}`} content={label}>
            {button}
          </TooltipWrapper>
        );
      })}
    </ButtonGroup>
  );
};
