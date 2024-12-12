import React, { useEffect } from "react";
import Loader from "@/components/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { Link, Navigate, useNavigate } from "@tanstack/react-router";
import NoData from "@/components/shared/NoData/NoData";
import useDatasetItemByName from "@/api/datasets/useDatasetItemByName";
import { Button } from "@/components/ui/button";

const RedirectDatasets = () => {
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: datasetByName, isPending: isPendingDatasetByName } =
    useDatasetItemByName(
      { datasetName: query.name || "" },
      { enabled: !!query.name && !query.id },
    );

  useEffect(() => {
    if (datasetByName?.id) {
      navigate({
        to: "/$workspaceName/datasets/$datasetId/items",
        params: {
          datasetId: datasetByName.id,
          workspaceName,
        },
      });
    }
  }, [datasetByName?.id, workspaceName, navigate]);

  if (query.id) {
    return <Navigate to={`/${workspaceName}/datasets/${query.id}/items`} />;
  }

  if (!isPendingDatasetByName && !datasetByName) {
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title="This dataset could not be found"
        message="The dataset you’re looking for doesn’t exist or has been deleted."
      >
        <div className="pt-5">
          <Link to="/$workspaceName/home" params={{ workspaceName }}>
            <Button>Back to Home</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  if (!query.id && !query.name) {
    return <NoData message="No dataset params set" />;
  }

  return <Loader />;
};

export default RedirectDatasets;
