import React from "react";
import { Button } from "@/components/ui/button";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

type OptimizationsNewHeaderProps = {
  isSubmitting: boolean;
  isFormValid: boolean;
  onSubmit: () => void;
  onCancel: () => void;
};

const OptimizationsNewHeader: React.FC<OptimizationsNewHeaderProps> = ({
  isSubmitting,
  isFormValid,
  onSubmit,
  onCancel,
}) => {
  return (
    <>
      <div className="mb-2 flex items-center justify-between">
        <h1 className="comet-title-l">Optimize a prompt</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={onSubmit}
            disabled={isSubmitting || !isFormValid}
          >
            {isSubmitting ? "Starting..." : "Optimize prompt"}
          </Button>
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
