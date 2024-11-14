import { useEffect } from "react";
import Loader from "@/components/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { Navigate, useNavigate } from "@tanstack/react-router";
import NoData from "@/components/shared/NoData/NoData";
import useDatasetItemByName from "@/api/datasets/useDatasetItemByName";

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
    return <NoData message="No dataset with this name" />;
  }

  if (!query.id && !query.name) {
    return <NoData message="No dataset params set" />;
  }

  return <Loader />;
};

export default RedirectDatasets;
