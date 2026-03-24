import { useNavigate } from "@tanstack/react-router";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

type UseNavigateToExperimentParams = {
  experimentIds?: string[];
  datasetId?: string;
  newExperiment?: boolean;
  datasetName?: string;
};

export const useNavigateToExperiment = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  return {
    navigate: ({
      experimentIds,
      datasetId,
      newExperiment,
      datasetName,
    }: UseNavigateToExperimentParams) => {
      if (experimentIds?.length && datasetId) {
        navigate({
          to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
          params: {
            workspaceName,
            projectId: activeProjectId!,
            datasetId,
          },
          search: {
            experiments: experimentIds,
          },
        });
      } else {
        navigate({
          to: "/$workspaceName/projects/$projectId/experiments",
          params: {
            workspaceName,
            projectId: activeProjectId!,
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
