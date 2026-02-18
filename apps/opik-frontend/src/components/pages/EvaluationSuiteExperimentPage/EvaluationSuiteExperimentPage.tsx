import React, { useEffect } from "react";
import { useParams } from "@tanstack/react-router";

import Loader from "@/components/shared/Loader/Loader";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useExperimentById from "@/api/datasets/useExperimentById";
import EvaluationSuiteExperimentDetails from "./EvaluationSuiteExperimentDetails";
import EvaluationSuiteExperimentItemsTab from "./EvaluationSuiteExperimentItemsTab";

const EvaluationSuiteExperimentPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const { suiteId, experimentId } = useParams({
    select: (params) => params,
    from: "/workspaceGuard/$workspaceName/evaluation-suites/$suiteId/experiments/$experimentId",
  });

  const { data: experiment, isPending: isExperimentPending } =
    useExperimentById({
      experimentId,
    });

  useEffect(() => {
    if (experiment?.name) {
      setBreadcrumbParam("experimentId", experimentId, experiment.name);
    }
    return () => setBreadcrumbParam("experimentId", experimentId, "");
  }, [experiment?.name, experimentId, setBreadcrumbParam]);

  if (isExperimentPending) {
    return <Loader />;
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        {experiment && (
          <EvaluationSuiteExperimentDetails
            experiment={experiment}
            suiteId={suiteId}
          />
        )}
      </PageBodyStickyContainer>
      <EvaluationSuiteExperimentItemsTab
        workspaceName={workspaceName}
        suiteId={suiteId}
        experimentId={experimentId}
      />
    </PageBodyScrollContainer>
  );
};

export default EvaluationSuiteExperimentPage;
