import React from "react";
import isUndefined from "lodash/isUndefined";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import ExperimentItemsTab from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab";
import ConfigurationTab from "@/components/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import ExperimentFeedbackScoresTab from "@/components/pages/CompareExperimentsPage/ExperimentFeedbackScoresTab/ExperimentFeedbackScoresTab";
import ExperimentsDashboardsTab from "@/components/pages/CompareExperimentsPage/ExperimentsDashboardsTab/ExperimentsDashboardsTab";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useDeepMemo from "@/hooks/useDeepMemo";
import { Experiment } from "@/types/datasets";
import CompareExperimentsDetails from "@/components/pages/CompareExperimentsPage/CompareExperimentsDetails/CompareExperimentsDetails";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { VIEW_TYPE } from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";
import useViewQueryParam from "@/components/pages-shared/dashboards/ViewSelector/hooks/useViewQueryParam";

const CompareExperimentsPage: React.FunctionComponent = () => {
  const [tab = "items", setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const { view, setView } = useViewQueryParam();

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

  const renderContent = () => {
    if (view === VIEW_TYPE.DETAILS) {
      return (
        <Tabs
          defaultValue="input"
          value={tab as string}
          onValueChange={setTab}
          className="min-w-min"
        >
          <PageBodyStickyContainer direction="horizontal" limitWidth>
            <TabsList variant="underline">
              <TabsTrigger variant="underline" value="items">
                Experiment items
              </TabsTrigger>
              <TabsTrigger variant="underline" value="config">
                Configuration
              </TabsTrigger>
              <TabsTrigger variant="underline" value="scores">
                Feedback scores
                <ExplainerIcon
                  className="ml-1"
                  {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
                />
              </TabsTrigger>
            </TabsList>
          </PageBodyStickyContainer>
          <TabsContent value="items">
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
      );
    }

    return <ExperimentsDashboardsTab experimentsIds={experimentsIds} />;
  };

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <CompareExperimentsDetails
          experimentsIds={experimentsIds}
          experiments={memorizedExperiments}
          isPending={isPending}
          view={view as VIEW_TYPE}
          onViewChange={setView}
        />
      </PageBodyStickyContainer>
      {renderContent()}
    </PageBodyScrollContainer>
  );
};

export default CompareExperimentsPage;
