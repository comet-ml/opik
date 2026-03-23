import React, { useEffect } from "react";
import {
  Navigate,
  Outlet,
  useLocation,
  useMatchRoute,
} from "@tanstack/react-router";
import useDatasetById from "@/api/datasets/useDatasetById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";

const EvaluationSuitePage = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const suiteId = useSuiteIdFromURL();

  const matchRoute = useMatchRoute();

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  const isSuiteRoot = matchRoute({
    to: "/$workspaceName/evaluation-suites/$suiteId",
  });

  // Evaluation suites are datasets under the hood — reuse the existing hook
  const { data } = useDatasetById({
    datasetId: suiteId,
  });

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam("suiteId", suiteId, data.name);
    }
  }, [suiteId, data?.name, setBreadcrumbParam]);

  if (isSuiteRoot) {
    return <Navigate to={pathname + "/items"} />;
  }

  return <Outlet />;
};

export default EvaluationSuitePage;
