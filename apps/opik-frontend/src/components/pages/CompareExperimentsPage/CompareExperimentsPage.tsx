import React, { useEffect } from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import ExperimentItemsTab from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab";
import ConfigurationTab from "@/components/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment } from "@/types/datasets";

const CompareExperimentsPage: React.FunctionComponent = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const [tab = "items", setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const [experimentsIds = []] = useQueryParam("experiments", JsonParam, {
    updateType: "replaceIn",
  });

  const isCompare = experimentsIds.length > 1;

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

  const experiment = memorizedExperiments[0];

  const title = !isCompare
    ? experiment?.name
    : `Compare (${experimentsIds.length})`;

  useEffect(() => {
    title && setBreadcrumbParam("compare", "compare", title);
    return () => setBreadcrumbParam("compare", "compare", "");
  }, [title, setBreadcrumbParam]);

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">{title}</h1>
      </div>
      <Tabs defaultValue="input" value={tab as string} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            Experiment items
          </TabsTrigger>
          <TabsTrigger variant="underline" value="config">
            Configuration
          </TabsTrigger>
        </TabsList>
        <TabsContent value="items">
          <ExperimentItemsTab experimentsIds={experimentsIds} />
        </TabsContent>
        <TabsContent value="config">
          <ConfigurationTab
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
