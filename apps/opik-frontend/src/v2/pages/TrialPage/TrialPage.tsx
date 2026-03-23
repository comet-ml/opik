import React, { useMemo, useState } from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, NumberParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import TrialItemsTab from "@/v2/pages/TrialPage/TrialsItemsTab/TrialItemsTab";
import TrialConfigurationSection from "@/v2/pages-shared/experiments/TrialConfigurationSection";
import TrialKPICards from "@/v2/pages/TrialPage/TrialKPICards";
import TrialDetails from "@/v2/pages/TrialPage/TrialDetails/TrialDetails";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useAppStore from "@/store/AppStore";
import { checkIsEvaluationSuite } from "@/lib/optimizations";
import { getObjectiveScoreValue } from "@/lib/feedback-scores";
import { keepPreviousData } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { usePermissions } from "@/contexts/PermissionsContext";
import { MAX_EXPERIMENTS_LOADED } from "@/lib/optimizations";

const TrialPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const [tab, setTab] = useState<string>("results");

  const [experimentsIds = []] = useQueryParam("trials", JsonParam, {
    updateType: "replaceIn",
  });
  const [trialNumber] = useQueryParam("trialNumber", NumberParam);

  const { optimizationId } = useParams({
    select: (params) => params,
    from: "/workspaceGuard/$workspaceName/optimizations/$optimizationId/trials",
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
      types: [EXPERIMENT_TYPE.TRIAL],
      page: 1,
      size: MAX_EXPERIMENTS_LOADED,
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

  const { baselineExperimentId, baselineScore } = useMemo(() => {
    const allExperiments = optimizationExperimentsData?.content ?? [];
    if (!allExperiments.length || !optimization?.objective_name) {
      return { baselineExperimentId: undefined, baselineScore: undefined };
    }
    const sorted = allExperiments
      .slice()
      .sort((a, b) => a.created_at.localeCompare(b.created_at));
    const baseline = sorted[0];
    const score = getObjectiveScoreValue(baseline, optimization.objective_name);
    return {
      baselineExperimentId: baseline.id,
      baselineScore: score ?? undefined,
    };
  }, [optimizationExperimentsData?.content, optimization?.objective_name]);

  const baselineExperiment = useMemo(() => {
    if (!baselineExperimentId) return undefined;
    const allExperiments = optimizationExperimentsData?.content ?? [];
    return allExperiments.find((exp) => exp.id === baselineExperimentId);
  }, [baselineExperimentId, optimizationExperimentsData?.content]);

  const parentExperiment = useMemo(() => {
    const experiment = memorizedExperiments[0];
    if (!experiment) return undefined;

    const allExperiments = optimizationExperimentsData?.content ?? [];

    // GEPA v2: parent is identified by parent_candidate_ids metadata
    const meta = experiment.metadata as Record<string, unknown> | undefined;
    const parentCandidateIds = meta?.parent_candidate_ids as
      | string[]
      | undefined;
    if (parentCandidateIds?.length) {
      const match = allExperiments.find((exp) => {
        const expMeta = exp.metadata as Record<string, unknown> | undefined;
        const candidateId = expMeta?.candidate_id as string | undefined;
        return candidateId && parentCandidateIds.includes(candidateId);
      });
      if (match) return match;
    }

    // Fallback: parent is the previous trial in chronological order
    const sorted = allExperiments
      .slice()
      .sort((a, b) => a.created_at.localeCompare(b.created_at));
    const currentIndex = sorted.findIndex((exp) => exp.id === experiment.id);
    if (currentIndex > 0) {
      return sorted[currentIndex - 1];
    }

    return undefined;
  }, [memorizedExperiments, optimizationExperimentsData?.content]);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <TrialDetails
          optimization={optimization}
          experiments={memorizedExperiments}
          baselineExperimentId={baselineExperimentId}
          baselineScore={baselineScore}
          trialNumber={trialNumber ?? undefined}
        />
      </PageBodyStickyContainer>

      {!isPending && memorizedExperiments.length > 0 && (
        <Tabs value={tab} onValueChange={setTab} className="min-w-min">
          <PageBodyStickyContainer
            direction="horizontal"
            limitWidth
            className="mb-6"
          >
            <TabsList variant="underline">
              <TabsTrigger variant="underline" value="results">
                Results
              </TabsTrigger>
              <TabsTrigger variant="underline" value="configuration">
                Configuration
              </TabsTrigger>
            </TabsList>
          </PageBodyStickyContainer>

          <TabsContent value="results" className="mt-0">
            <PageBodyStickyContainer
              direction="horizontal"
              limitWidth
              className="mb-6"
            >
              <TrialKPICards
                experiments={memorizedExperiments}
                allOptimizationExperiments={
                  optimizationExperimentsData?.content ?? []
                }
                objectiveName={optimization?.objective_name}
                isEvaluationSuite={isEvaluationSuite}
              />
            </PageBodyStickyContainer>

            {canViewDatasets && (
              <>
                <PageBodyStickyContainer direction="horizontal" limitWidth>
                  <h2 className="comet-title-s mb-4">Evaluation results</h2>
                </PageBodyStickyContainer>
                <TrialItemsTab
                  objectiveName={optimization?.objective_name}
                  datasetId={optimization?.dataset_id ?? ""}
                  experimentsIds={experimentsIds}
                  experiments={memorizedExperiments}
                  isEvaluationSuite={isEvaluationSuite}
                />
              </>
            )}
          </TabsContent>

          <TabsContent value="configuration" className="mt-0">
            <PageBodyStickyContainer
              direction="horizontal"
              limitWidth
              className="mb-6"
            >
              <TrialConfigurationSection
                experiments={memorizedExperiments}
                referenceExperiment={baselineExperiment}
                parentExperiment={parentExperiment}
                studioConfig={optimization?.studio_config}
              />
            </PageBodyStickyContainer>
          </TabsContent>
        </Tabs>
      )}
    </PageBodyScrollContainer>
  );
};

export default TrialPage;
