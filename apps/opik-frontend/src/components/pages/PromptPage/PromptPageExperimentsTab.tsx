import React from "react";
import { TabsContent, TabsTrigger } from "@/components/ui/tabs";
import ExperimentsTab from "@/components/pages/PromptPage/ExperimentsTab/ExperimentsTab";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

export interface PromptPageExperimentsTabProps {
  promptId: string;
}

export const PromptPageExperimentsTabTrigger: React.FC<{
  canViewExperiments: boolean;
}> = ({ canViewExperiments }) => {
  if (!canViewExperiments) return null;

  return (
    <TabsTrigger variant="underline" value="experiments">
      Experiments
      <ExplainerIcon
        className="ml-1"
        {...EXPLAINERS_MAP[
          EXPLAINER_ID.why_do_i_have_experiments_in_the_prompt_library
        ]}
      />
    </TabsTrigger>
  );
};

export const PromptPageExperimentsTabContent: React.FC<
  PromptPageExperimentsTabProps & { canViewExperiments: boolean }
> = ({ promptId, canViewExperiments }) => {
  if (!canViewExperiments) return null;

  return (
    <TabsContent value="experiments">
      <ExperimentsTab promptId={promptId} />
    </TabsContent>
  );
};
