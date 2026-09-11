import { useEffect } from "react";
import {
  Navigate,
  Outlet,
  useLocation,
  useMatchRoute,
  useParams,
} from "@tanstack/react-router";
import useDatasetById from "@/api/datasets/useDatasetById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useDatasetEntityIdFromURL } from "@/v2/hooks/useDatasetEntityIdFromURL";

const DatasetDetailPage = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const datasetId = useDatasetEntityIdFromURL();

  const allParams = useParams({ strict: false }) as Record<string, string>;
  const breadcrumbKey = "datasetId" in allParams ? "datasetId" : "suiteId";

  const matchRoute = useMatchRoute();

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  const isDetailRoot =
    matchRoute({
      to: "/$workspaceName/projects/$projectId/datasets/$datasetId",
    }) ||
    matchRoute({
      to: "/$workspaceName/projects/$projectId/test-suites/$suiteId",
    });

  const { data } = useDatasetById({
    datasetId,
  });

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam(breadcrumbKey, datasetId, data.name);
    }
  }, [datasetId, data?.name, breadcrumbKey, setBreadcrumbParam]);

  if (isDetailRoot) {
    return <Navigate to={pathname + "/items"} />;
  }

  return <Outlet />;
};

export default DatasetDetailPage;
