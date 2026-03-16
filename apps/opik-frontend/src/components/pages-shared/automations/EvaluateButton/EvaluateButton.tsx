import React from "react";
import { Brain } from "lucide-react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { usePermissions } from "@/contexts/PermissionsContext";

type EvaluateButtonProps = {
  isNoRules: boolean;
  disabled: boolean;
  onClick: () => void;
};

const EvaluateButton: React.FunctionComponent<EvaluateButtonProps> = ({
  disabled,
  isNoRules,
  onClick,
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
          variant="outline"
          size="icon-sm"
          onClick={onClick}
          disabled={disabled || noEvaluateOptions}
        >
          <Brain />
        </Button>
      </div>
    </TooltipWrapper>
  );
};

export default EvaluateButton;
