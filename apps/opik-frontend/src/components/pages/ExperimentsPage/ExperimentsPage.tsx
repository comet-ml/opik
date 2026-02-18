import React, { useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";

import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import useAppStore from "@/store/AppStore";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { DATASET_TYPE } from "@/types/datasets";
import EvaluationSuitesTab from "./EvaluationSuitesTab/EvaluationSuitesTab";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";

enum EXPERIMENTS_TABS {
  EVALUATION_SUITES = "evaluation-suites",
  GENERAL_DATASETS = "general-datasets",
}

const DEFAULT_TAB = EXPERIMENTS_TABS.EVALUATION_SUITES;

const ExperimentsPage: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: datasetsData } = useDatasetsList(
    { workspaceName, type: DATASET_TYPE.DATASET, page: 1, size: 1 },
    { placeholderData: keepPreviousData },
  );
  const showGeneralDatasetsTab = (datasetsData?.total ?? 0) > 0;
  const tabContentClassName = showGeneralDatasetsTab ? "mt-0" : undefined;

  const [tab, setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  useEffect(() => {
    if (!tab) {
      setTab(DEFAULT_TAB, "replaceIn");
    }
  }, [tab, setTab]);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="pb-1 pt-6"
        direction="horizontal"
        limitWidth
      >
        <h1 className="comet-title-l truncate break-words">Experiments</h1>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerDescription
          {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_experiment]}
        />
      </PageBodyStickyContainer>
      <Tabs
        defaultValue={DEFAULT_TAB}
        value={tab as string}
        onValueChange={setTab}
      >
        {showGeneralDatasetsTab && (
          <PageBodyStickyContainer
            className="pt-4"
            direction="horizontal"
            limitWidth
          >
            <TabsList variant="underline">
              <TabsTrigger
                variant="underline"
                value={EXPERIMENTS_TABS.EVALUATION_SUITES}
              >
                Evaluation suites
              </TabsTrigger>
              <TabsTrigger
                variant="underline"
                value={EXPERIMENTS_TABS.GENERAL_DATASETS}
              >
                General datasets
              </TabsTrigger>
            </TabsList>
          </PageBodyStickyContainer>
        )}
        <TabsContent
          className={tabContentClassName}
          value={EXPERIMENTS_TABS.EVALUATION_SUITES}
        >
          <EvaluationSuitesTab />
        </TabsContent>
        <TabsContent
          className={tabContentClassName}
          value={EXPERIMENTS_TABS.GENERAL_DATASETS}
        >
          <GeneralDatasetsTab />
        </TabsContent>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
