import React from "react";

import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";

const ExperimentsPage: React.FC = () => {
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
      <GeneralDatasetsTab />
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
