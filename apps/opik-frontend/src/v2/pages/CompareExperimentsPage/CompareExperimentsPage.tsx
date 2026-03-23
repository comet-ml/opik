import React from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import ExperimentItemsTab from "@/v2/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab";
import ConfigurationTab from "@/v2/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import ExperimentFeedbackScoresTab from "@/v2/pages/CompareExperimentsPage/ExperimentFeedbackScoresTab/ExperimentFeedbackScoresTab";
import ExperimentAssertionsTab from "@/v2/pages/CompareExperimentsPage/ExperimentAssertionsTab/ExperimentAssertionsTab";
import ExperimentInsightsTab from "@/v2/pages/CompareExperimentsPage/ExperimentInsightsTab/ExperimentInsightsTab";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment } from "@/types/datasets";
import { isEvalSuiteExperiment } from "@/lib/experiments";
import CompareExperimentsDetails from "@/v2/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

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

  const isEvalSuite = isEvalSuiteExperiment(memorizedExperiments[0]);

  const hasAssertionScores = memorizedExperiments.some(
    (e) => (e.assertion_scores ?? []).length > 0,
  );

  const showScoresTab =
    memorizedExperiments.length > 0 && (!isEvalSuite || hasAssertionScores);

  const renderContent = () => {
    return (
      <Tabs
        defaultValue="items"
        value={tab as string}
        onValueChange={setTab}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="items">
              Experiment items
            </TabsTrigger>
            <TabsTrigger variant="underline" value="insights">
              Insights
            </TabsTrigger>
            <TabsTrigger variant="underline" value="config">
              Configuration
            </TabsTrigger>
            {showScoresTab && (
              <TabsTrigger variant="underline" value="scores">
                {isEvalSuite ? "Assertions" : "Feedback scores"}
                {!isEvalSuite && (
                  <ExplainerIcon
                    className="ml-1"
                    {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
                  />
                )}
              </TabsTrigger>
            )}
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value="items">
          <ExperimentItemsTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isEvalSuite={isEvalSuite}
          />
        </TabsContent>
        <TabsContent value="insights">
          <ExperimentInsightsTab experimentsIds={experimentsIds} />
        </TabsContent>
        <TabsContent value="config">
          <ConfigurationTab
            experimentsIds={experimentsIds}
            experiments={memorizedExperiments}
            isPending={isPending}
          />
        </TabsContent>
        {showScoresTab && (
          <TabsContent value="scores">
            {isEvalSuite ? (
              <ExperimentAssertionsTab
                experimentsIds={experimentsIds}
                experiments={memorizedExperiments}
                isPending={isPending}
              />
            ) : (
              <ExperimentFeedbackScoresTab
                experimentsIds={experimentsIds}
                experiments={memorizedExperiments}
                isPending={isPending}
              />
            )}
          </TabsContent>
        )}
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
