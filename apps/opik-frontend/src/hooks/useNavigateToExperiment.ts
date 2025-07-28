import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type UseNavigateToExperimentParams = {
  experimentIds?: string[];
  datasetId?: string;
  newExperiment?: boolean;
  datasetName?: string;
};

export const useNavigateToExperiment = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return {
    navigate: ({
      experimentIds,
      datasetId,
      newExperiment,
      datasetName,
    }: UseNavigateToExperimentParams) => {
      if (experimentIds?.length && datasetId) {
        navigate({
          to: "/$workspaceName/experiments/$datasetId/compare",
          params: {
            workspaceName,
            datasetId,
          },
          search: {
            experiments: experimentIds,
          },
        });
      } else {
        navigate({
          to: "/$workspaceName/experiments",
          params: {
            workspaceName,
          },
          search: {
            new: {
              experiment: newExperiment,
              datasetName,
            },
          },
        });
      }
    },
  };
};
