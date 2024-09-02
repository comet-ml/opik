import React, { useEffect } from "react";
import {
  Link,
  Navigate,
  Outlet,
  useLocation,
  useMatchRoute,
} from "@tanstack/react-router";
import useDatasetById from "@/api/datasets/useDatasetById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import useAppStore from "@/store/AppStore";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

type TAB_ID = "items" | "experiments" | string | undefined;

const DatasetPage = () => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const datasetId = useDatasetIdFromURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const matchRoute = useMatchRoute();

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  const isDatasetRoot = matchRoute({
    to: "/$workspaceName/datasets/$datasetId",
  });

  const isItemsTab = matchRoute({
    to: "/$workspaceName/datasets/$datasetId/items",
    fuzzy: true,
  });

  const isExperimentsTab = matchRoute({
    to: "/$workspaceName/datasets/$datasetId/experiments",
    fuzzy: true,
  });

  const activeTab: TAB_ID = isItemsTab
    ? "items"
    : isExperimentsTab
      ? "experiments"
      : undefined;

  const { data } = useDatasetById({
    datasetId,
  });

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam("datasetId", datasetId, data.name);
    }
  }, [datasetId, data?.name, setBreadcrumbParam]);

  if (isDatasetRoot) {
    return <Navigate to={pathname + "/experiments"} />;
  }

  if (isItemsTab || isExperimentsTab) {
    return (
      <div>
        <div className="flex flex-col gap-6 pb-4 pt-6">
          <div className="flex items-center">
            <h2 className="comet-title-l">{data?.name}</h2>
          </div>
          <div className="flex items-center justify-between">
            <ToggleGroup value={activeTab} type="single">
              <ToggleGroupItem value="experiments" asChild>
                <Link
                  to={"/$workspaceName/datasets/$datasetId/experiments"}
                  params={{ workspaceName, datasetId }}
                >
                  Experiments
                </Link>
              </ToggleGroupItem>
              <ToggleGroupItem value="items" asChild>
                <Link
                  to={"/$workspaceName/datasets/$datasetId/items"}
                  params={{ workspaceName, datasetId }}
                >
                  Dataset items
                </Link>
              </ToggleGroupItem>
            </ToggleGroup>
          </div>
        </div>
        <Outlet />
      </div>
    );
  }

  return <Outlet />;
};

export default DatasetPage;
