import React, { useMemo } from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import ExperimentItemsTab from "@/v2/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab";
import ConfigurationTab from "@/v2/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import ExperimentFeedbackScoresTab from "@/v2/pages/CompareExperimentsPage/ExperimentFeedbackScoresTab/ExperimentFeedbackScoresTab";
import ExperimentInsightsTab from "@/v2/pages/CompareExperimentsPage/ExperimentInsightsTab/ExperimentInsightsTab";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useDeepMemo from "@/hooks/useDeepMemo";
import { useActiveProjectId } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import {
  EXPERIMENT_TAB,
  ExperimentTabId,
  getAvailableExperimentTabs,
  isExperimentTabId,
  isTestSuiteExperiment,
} from "@/lib/experiments";
import CompareExperimentsDetails from "@/v2/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails";
import ExperimentLogsTab from "@/v2/pages/CompareExperimentsPage/ExperimentLogsTab/ExperimentLogsTab";

const EXPERIMENT_TAB_LABELS: Record<ExperimentTabId, string> = {
  [EXPERIMENT_TAB.items]: "Results",
  [EXPERIMENT_TAB.insights]: "Insights",
  [EXPERIMENT_TAB.config]: "Configuration",
  [EXPERIMENT_TAB.scores]: "Feedback scores",
  [EXPERIMENT_TAB.logs]: "Logs",
};

const CompareExperimentsPage: React.FunctionComponent = () => {
  const [tab = "items", setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const [requestedExperimentsIds = []] = useQueryParam(
    "experiments",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const activeProjectId = useActiveProjectId();

  // Membership comes from the project-scoped list endpoint, not from
  // experiment.project_id, so an experiment whose traces span several projects
  // is shown in every project that lists it.
  const { data: projectExperimentsData, isLoading: isProjectScopeLoading } =
    useExperimentsList(
      {
        projectId: activeProjectId ?? undefined,
        experimentIds: requestedExperimentsIds,
        types: Object.values(EXPERIMENT_TYPE),
        page: 1,
        size: requestedExperimentsIds.length,
      },
      {
        enabled: Boolean(activeProjectId) && requestedExperimentsIds.length > 0,
      },
    );

  const experimentsIds: string[] = useMemo(() => {
    const projectExperimentIds = new Set(
      (projectExperimentsData?.content ?? []).map((e) => e.id),
    );
    return requestedExperimentsIds.filter((id: string) =>
      projectExperimentIds.has(id),
    );
  }, [requestedExperimentsIds, projectExperimentsData]);

  const response = useExperimentsByIds({
    experimentsIds,
  });

  const isPending = response.some((r) => r.isPending);

  const experiments: Experiment[] = response
    .map((r) => r.data)
    .filter((e) => !isUndefined(e));

  const memorizedExperiments: Experiment[] = useDeepMemo(
    () => experiments,
    [experiments],
  );

  const isTestSuite = isTestSuiteExperiment(memorizedExperiments[0]);

  const availableTabs = useMemo(
    () => getAvailableExperimentTabs(memorizedExperiments),
    [memorizedExperiments],
  );

  const effectiveTab =
    isExperimentTabId(tab) && availableTabs.includes(tab)
      ? tab
      : EXPERIMENT_TAB.items;

  if (isProjectScopeLoading) {
    return <Loader />;
  }

  const renderContent = () => {
    return (
      <Tabs
        defaultValue="items"
        value={effectiveTab}
        onValueChange={setTab}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="segmented-primary">
            {availableTabs.map((tabId) => (
              <TabsTrigger
                key={tabId}
                variant="segmented-primary"
                value={tabId}
              >
                {EXPERIMENT_TAB_LABELS[tabId]}
              </TabsTrigger>
            ))}
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value={EXPERIMENT_TAB.items}>
          <ExperimentItemsTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isTestSuite={isTestSuite}
          />
        </TabsContent>
        {availableTabs.includes(EXPERIMENT_TAB.insights) && (
          <TabsContent value={EXPERIMENT_TAB.insights}>
            <ExperimentInsightsTab experimentsIds={experimentsIds} />
          </TabsContent>
        )}
        <TabsContent value={EXPERIMENT_TAB.config}>
          <ConfigurationTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
        {availableTabs.includes(EXPERIMENT_TAB.scores) && (
          <TabsContent value={EXPERIMENT_TAB.scores}>
            <ExperimentFeedbackScoresTab
              experimentsIds={experimentsIds}
              experiments={memorizedExperiments}
              isPending={isPending}
            />
          </TabsContent>
        )}
        <TabsContent value={EXPERIMENT_TAB.logs}>
          <ExperimentLogsTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
          />
        </TabsContent>
      </Tabs>
    );
  };

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <CompareExperimentsDetails
          experimentsIds={experimentsIds}
          experiments={memorizedExperiments}
        />
      </PageBodyStickyContainer>
      {renderContent()}
    </PageBodyScrollContainer>
  );
};

export default CompareExperimentsPage;
