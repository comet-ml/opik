import React, { useEffect } from "react";
import {
  Navigate,
  Outlet,
  useLocation,
  useMatchRoute,
} from "@tanstack/react-router";
import useDatasetById from "@/api/datasets/useDatasetById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";

const DatasetPage = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const datasetId = useDatasetIdFromURL();
  const matchRoute = useMatchRoute();

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  const isDatasetRoot = matchRoute({
    to: "/$workspaceName/datasets/$datasetId",
  });

  const { data } = useDatasetById({
    datasetId,
  });

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam("datasetId", datasetId, data.name);
    }
  }, [datasetId, data?.name, setBreadcrumbParam]);

  if (isDatasetRoot) {
    return <Navigate to={pathname + "/items"} />;
  }

  return <Outlet />;
};

export default DatasetPage;
