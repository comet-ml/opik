import React from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import CompareExperimentsDetails from "@/components/pages/CompareExperimentsPage/CompareExperimentsDetails";
import ExperimentItemsTab from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab";
import ConfigurationTab from "@/components/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import ExperimentFeedbackScoresTab from "@/components/pages/CompareExperimentsPage/ExperimentFeedbackScoresTab/ExperimentFeedbackScoresTab";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment } from "@/types/datasets";

const CompareExperimentsPage: React.FunctionComponent = () => {
  const [tab = "items", setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const [experimentsIds = []] = useQueryParam("experiments", JsonParam, {
    updateType: "replaceIn",
  });

  const response = useExperimentsByIds({
    experimentsIds,
  });

  const isPending = response.reduce<boolean>(
    (acc, r) => acc || r.isPending,
    false,
  );

  const experiments: Experiment[] = response
    .map((r) => r.data)
    .filter((e) => !isUndefined(e));

  const memorizedExperiments: Experiment[] = useDeepMemo(() => {
    return experiments ?? [];
  }, [experiments]);

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <CompareExperimentsDetails
        experimentsIds={experimentsIds}
        experiments={memorizedExperiments}
      />
      <Tabs 
        defaultValue="input" 
        value={tab as string} 
        onValueChange={setTab}
        className="flex-1 flex flex-col min-h-0 overflow-hidden"
      >
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            Experiment items
          </TabsTrigger>
          <TabsTrigger variant="underline" value="config">
            Configuration
          </TabsTrigger>
          <TabsTrigger variant="underline" value="scores">
            Feedback scores
          </TabsTrigger>
        </TabsList>
        <TabsContent value="items" className="h-full flex flex-col min-h-0 overflow-hidden">
          <ExperimentItemsTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
          />
        </TabsContent>
        <TabsContent value="config">
          <ConfigurationTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
        <TabsContent value="scores">
          <ExperimentFeedbackScoresTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default CompareExperimentsPage;
