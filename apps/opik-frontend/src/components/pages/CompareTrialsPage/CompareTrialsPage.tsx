import React, { useMemo } from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import PromptTab from "@/components/pages/CompareTrialsPage/PromptTab/PromptTab";
import TrialItemsTab from "@/components/pages/CompareTrialsPage/TrialsItemsTab/TrialItemsTab";
import ConfigurationTab from "@/components/pages/CompareTrialsPage/ConfigurationTab/ConfigurationTab";
import CompareTrialsDetails from "@/components/pages/CompareTrialsPage/CompareTrialsDetails/CompareTrialsDetails";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useAppStore from "@/store/AppStore";
import { checkIsEvaluationSuite } from "@/lib/optimizations";
import { keepPreviousData } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";

const CompareTrialsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [tab = "prompt", setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const [experimentsIds = []] = useQueryParam("trials", JsonParam, {
    updateType: "replaceIn",
  });

  const { datasetId, optimizationId } = useParams({
    select: (params) => params,
    from: "/workspaceGuard/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
  });

  const response = useExperimentsByIds({
    experimentsIds,
  });

  const { data: optimization } = useOptimizationById(
    {
      optimizationId,
    },
    {
      placeholderData: keepPreviousData,
      enabled: !!optimizationId,
    },
  );

  const { data: optimizationExperimentsData } = useExperimentsList(
    {
      workspaceName,
      optimizationId,
      types: [EXPERIMENT_TYPE.TRIAL, EXPERIMENT_TYPE.MINI_BATCH],
      page: 1,
      size: 100,
    },
    {
      enabled: !!optimizationId,
    },
  );

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

  const isEvaluationSuite = useMemo(() => {
    const allExperiments = [
      ...memorizedExperiments,
      ...(optimizationExperimentsData?.content ?? []),
    ];
    return checkIsEvaluationSuite(allExperiments);
  }, [memorizedExperiments, optimizationExperimentsData?.content]);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <CompareTrialsDetails
          optimization={optimization}
          experimentsIds={experimentsIds}
          experiments={memorizedExperiments}
          isEvaluationSuite={isEvaluationSuite}
        />
      </PageBodyStickyContainer>
      <Tabs
        defaultValue="input"
        value={tab as string}
        onValueChange={setTab}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="prompt">
              Prompt
            </TabsTrigger>
            <TabsTrigger variant="underline" value="items">
              Trial items
            </TabsTrigger>
            <TabsTrigger variant="underline" value="config">
              Configuration
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value="prompt">
          <PromptTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
        <TabsContent value="items">
          <TrialItemsTab
            objectiveName={optimization?.objective_name}
            datasetId={datasetId}
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isEvaluationSuite={isEvaluationSuite}
          />
        </TabsContent>
        <TabsContent value="config">
          <ConfigurationTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default CompareTrialsPage;
