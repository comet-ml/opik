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
  getAvailableExperimentTabs,
  isTestSuiteExperiment,
} from "@/lib/experiments";
import CompareExperimentsDetails from "@/v2/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails";
import ExperimentLogsTab from "@/v2/pages/CompareExperimentsPage/ExperimentLogsTab/ExperimentLogsTab";

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

  const showScoresTab = availableTabs.includes(EXPERIMENT_TAB.scores);

  const effectiveTab = availableTabs.includes(
    tab as (typeof availableTabs)[number],
  )
    ? (tab as string)
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
            <TabsTrigger variant="segmented-primary" value="items">
              Results
            </TabsTrigger>
            {!isTestSuite && (
              <TabsTrigger variant="segmented-primary" value="insights">
                Insights
              </TabsTrigger>
            )}
            <TabsTrigger variant="segmented-primary" value="config">
              Configuration
            </TabsTrigger>
            {showScoresTab && (
              <TabsTrigger variant="segmented-primary" value="scores">
                Feedback scores
              </TabsTrigger>
            )}
            <TabsTrigger variant="segmented-primary" value="logs">
              Logs
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value="items">
          <ExperimentItemsTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isTestSuite={isTestSuite}
          />
        </TabsContent>
        {!isTestSuite && (
          <TabsContent value="insights">
            <ExperimentInsightsTab experimentsIds={experimentsIds} />
          </TabsContent>
        )}
        <TabsContent value="config">
          <ConfigurationTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
        {showScoresTab && (
          <TabsContent value="scores">
            <ExperimentFeedbackScoresTab
              experimentsIds={experimentsIds}
              experiments={memorizedExperiments}
              isPending={isPending}
            />
          </TabsContent>
        )}
        <TabsContent value="logs">
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
