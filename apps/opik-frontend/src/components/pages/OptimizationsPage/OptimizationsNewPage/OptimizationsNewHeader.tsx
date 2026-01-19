import React from "react";
import { Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type OptimizationsNewHeaderProps = {
  isSubmitting: boolean;
  isFormValid: boolean;
  onSubmit: () => void;
  onCancel: () => void;
  onDownload: () => void;
};

const OptimizationsNewHeader: React.FC<OptimizationsNewHeaderProps> = ({
  isSubmitting,
  isFormValid,
  onSubmit,
  onCancel,
  onDownload,
}) => {
  const isDisabled = isSubmitting || !isFormValid;

  return (
    <>
      <div className="mb-2 flex items-center justify-between">
        <h1 className="comet-title-l">Optimize a prompt</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onCancel}>
            Cancel
          </Button>
          <Button size="sm" onClick={onSubmit} disabled={isDisabled}>
            {isSubmitting ? "Starting..." : "Optimize prompt"}
          </Button>
          <TooltipWrapper content="Download optimization code as Python file">
            <Button
              variant="outline"
              size="sm"
              onClick={onDownload}
              disabled={isDisabled}
            >
              <Download className="size-4" />
            </Button>
          </TooltipWrapper>
        </div>
      </div>
      <ExplainerDescription
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimization_config]}
        className="mb-6"
      />
    </>
  );
};

export default OptimizationsNewHeader;
