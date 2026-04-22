import React from "react";
import { Brain } from "lucide-react";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { usePermissions } from "@/contexts/PermissionsContext";

type EvaluateButtonProps = {
  isNoRules: boolean;
  disabled: boolean;
  onClick: () => void;
  buttonVariant?: "outline" | "ghost" | "ghostInverted";
  label?: string;
};

const EvaluateButton: React.FunctionComponent<EvaluateButtonProps> = ({
  disabled,
  isNoRules,
  onClick,
  buttonVariant = "outline",
  label,
}) => {
  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const noEvaluateOptions = !canUpdateOnlineEvaluationRules && isNoRules;

  const getTooltip = () => {
    if (disabled) return "";

    if (noEvaluateOptions) {
      return "No online evaluation rules assigned to this project";
    }

    return "Evaluate";
  };

  return (
    <TooltipWrapper content={getTooltip()}>
      <div>
        <Button
          variant={buttonVariant}
          size={label ? "sm" : "icon-sm"}
          onClick={onClick}
          disabled={disabled || noEvaluateOptions}
        >
          <Brain className="size-3.5" />
          {label && <span className="ml-2">{label}</span>}
        </Button>
      </div>
    </TooltipWrapper>
  );
};

export default EvaluateButton;
