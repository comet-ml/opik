import React from "react";
import NavigationTag from "@/components/shared/NavigationTag";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";

export interface PlaygroundExperimentsLinkProps {
  plainDatasetId: string;
  isSingleExperiment: boolean;
  experimentIds: string[];
}

const PlaygroundExperimentsLink: React.FC<
  PlaygroundExperimentsLinkProps & { canViewExperiments: boolean }
> = ({
  plainDatasetId,
  isSingleExperiment,
  experimentIds,
  canViewExperiments,
}) => {
  if (!canViewExperiments) return null;

  return (
    <div className="mt-2.5">
      <NavigationTag
        resource={RESOURCE_TYPE.experiment}
        id={plainDatasetId}
        name={isSingleExperiment ? "Experiment" : "Experiments"}
        className="h-8"
        search={{
          experiments: experimentIds,
        }}
        tooltipContent={
          isSingleExperiment
            ? "Your run was stored in this experiment. Explore your results to find insights."
            : "Your run was stored in experiments. Explore comparison results to get insights."
        }
      />
    </div>
  );
};

export default PlaygroundExperimentsLink;
