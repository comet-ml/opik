import React, { useMemo } from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, useQueryParam } from "use-query-params";

import TrialItemsTab from "@/components/pages/CompareTrialsPage/TrialsItemsTab/TrialItemsTab";
import TrialConfigurationSection from "@/components/pages-shared/experiments/TrialConfigurationSection";
import TrialKPICards from "@/components/pages/CompareTrialsPage/TrialKPICards";
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
import { getFeedbackScoreValue } from "@/lib/feedback-scores";
import { keepPreviousData } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { usePermissions } from "@/contexts/PermissionsContext";
import { OPTIMIZER_TYPE } from "@/types/optimizations";

const CompareTrialsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

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

  const { baselineExperimentId, baselineScore } = useMemo(() => {
    const allExperiments = optimizationExperimentsData?.content ?? [];
    if (!allExperiments.length || !optimization?.objective_name) {
      return { baselineExperimentId: undefined, baselineScore: undefined };
    }
    const sorted = allExperiments
      .slice()
      .sort((a, b) => a.created_at.localeCompare(b.created_at));
    const baseline = sorted[0];
    const score = getFeedbackScoreValue(
      baseline.feedback_scores ?? [],
      optimization.objective_name,
    );
    return {
      baselineExperimentId: baseline.id,
      baselineScore: score ?? undefined,
    };
  }, [optimizationExperimentsData?.content, optimization?.objective_name]);

  const parentExperiment = useMemo(() => {
    const allExperiments = optimizationExperimentsData?.content ?? [];
    if (!memorizedExperiments.length || !allExperiments.length)
      return undefined;

    const currentMeta = memorizedExperiments[0]?.metadata as
      | Record<string, unknown>
      | undefined;
    const parentCandidateIds = currentMeta?.parent_candidate_ids as
      | string[]
      | undefined;

    if (!parentCandidateIds?.length) return undefined;

    const parentCandidateId = parentCandidateIds[0];
    return allExperiments.find((exp) => {
      const meta = exp.metadata as Record<string, unknown> | undefined;
      return meta?.candidate_id === parentCandidateId;
    });
  }, [memorizedExperiments, optimizationExperimentsData?.content]);

  const isGepa =
    optimization?.studio_config?.optimizer?.type === OPTIMIZER_TYPE.GEPA ||
    (optimization?.metadata as Record<string, unknown> | undefined)
      ?.optimizer === "GepaOptimizer";

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <CompareTrialsDetails
          optimization={optimization}
          experimentsIds={experimentsIds}
          experiments={memorizedExperiments}
          isEvaluationSuite={isEvaluationSuite}
          baselineExperimentId={baselineExperimentId}
          baselineScore={baselineScore}
        />
      </PageBodyStickyContainer>

      {!isPending && memorizedExperiments.length > 0 && (
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
          />
        </PageBodyStickyContainer>
      )}

      {!isPending && memorizedExperiments.length > 0 && (
        <PageBodyStickyContainer
          direction="horizontal"
          limitWidth
          className="mb-6"
        >
          <TrialConfigurationSection
            experiments={memorizedExperiments}
            referenceExperiment={parentExperiment}
          />
        </PageBodyStickyContainer>
      )}

      {canViewDatasets && (
        <>
          <PageBodyStickyContainer direction="horizontal" limitWidth>
            <h2 className="comet-title-s mb-4">Evaluation Results</h2>
          </PageBodyStickyContainer>
          <TrialItemsTab
            objectiveName={optimization?.objective_name}
            datasetId={datasetId}
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isEvaluationSuite={isEvaluationSuite}
            showMinibatch={isGepa}
          />
        </>
      )}
    </PageBodyScrollContainer>
  );
};

export default CompareTrialsPage;
