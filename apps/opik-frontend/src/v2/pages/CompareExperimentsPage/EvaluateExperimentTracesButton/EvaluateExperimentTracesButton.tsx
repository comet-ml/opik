import React from "react";

import EvaluateButton from "@/v2/pages-shared/automations/EvaluateButton/EvaluateButton";
import RunEvaluationDialog from "@/v2/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";
import { Experiment } from "@/types/datasets";
import useEvaluateExperimentTraces from "./useEvaluateExperimentTraces";

type EvaluateExperimentTracesButtonProps = {
  experiment?: Experiment;
};

const EvaluateExperimentTracesButton: React.FC<
  EvaluateExperimentTracesButtonProps
> = ({ experiment }) => {
  if (!experiment?.project_id) return null;

  return <Inner experiment={experiment} projectId={experiment.project_id} />;
};

const Inner: React.FC<{ experiment: Experiment; projectId: string }> = ({
  experiment,
  projectId,
}) => {
  const {
    open,
    setOpen,
    traceIds,
    isFetching,
    rules,
    isRulesLoading,
    handleClick,
  } = useEvaluateExperimentTraces({ experiment });

  return (
    <>
      <RunEvaluationDialog
        open={open}
        setOpen={setOpen}
        projectId={projectId}
        entityIds={traceIds}
        entityType="trace"
        rules={rules}
        isLoading={isRulesLoading}
      />
      <EvaluateButton
        isNoRules={!rules?.length}
        disabled={isFetching}
        label="Evaluate"
        onClick={handleClick}
      />
    </>
  );
};

export default EvaluateExperimentTracesButton;
