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
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment } from "@/types/datasets";
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

  const [experimentsIds = []] = useQueryParam("experiments", JsonParam, {
    updateType: "replaceIn",
  });

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
