import React, { useEffect } from "react";
import {
  Outlet,
  useLocation,
  useMatchRoute,
  useNavigate,
} from "@tanstack/react-router";
import useDatasetById from "@/api/datasets/useDatasetById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

const DatasetPage = () => {
  const navigate = useNavigate();
  const [tab, setTab] = React.useState("items");

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  useEffect(() => {
    if (pathname.endsWith("/experiments")) {
      setTab("experiments");
    } else {
      setTab("items");
    }
  }, [pathname]);

  const handleTabChange = (value: string) => {
    setTab(value);

    navigate({
      to: `${pathname.replace(/\/(items|experiments)$/, "")}/${value}`,
    });
  };
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const datasetId = useDatasetIdFromURL();
  const matchRoute = useMatchRoute();

  const isDatasetRoot = matchRoute({
    to: "/$workspaceName/datasets/$datasetId",
  });

  useEffect(() => {
    if (isDatasetRoot) {
      navigate({ to: pathname + "/items" });
    }
  }, [isDatasetRoot, navigate, pathname]);

  const { data } = useDatasetById({
    datasetId,
  });

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam("datasetId", datasetId, data.name);
    }
  }, [datasetId, data?.name, setBreadcrumbParam]);

  return (
    <div className="flex flex-col gap-6 p-6">
      {/* Dataset header */}
      {data?.name && (
        <div>
          <h1 className="text-2xl font-bold">{data.name}</h1>
        </div>
      )}

      {/* Tabs */}
      <Tabs value={tab} onValueChange={handleTabChange}>
        <TabsList>
          <TabsTrigger value="items">Dataset Items</TabsTrigger>
          <TabsTrigger value="experiments">Experiments</TabsTrigger>
        </TabsList>

        <TabsContent value="items" className="mt-6">
          <Outlet />
        </TabsContent>

        <TabsContent value="experiments" className="mt-6">
          <Outlet />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default DatasetPage;
