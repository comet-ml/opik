import { useEffect } from "react";
import Loader from "@/components/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { Navigate, useNavigate } from "@tanstack/react-router";
import useProjectByName from "@/api/projects/useProjectByName";
import NoData from "@/components/shared/NoData/NoData";

const RedirectProjects = () => {
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: projectByName, isPending: isPendingProjectByName } =
    useProjectByName(
      { projectName: query.name || "" },
      { enabled: !!query.name && !query.id },
    );

  useEffect(() => {
    if (projectByName?.id) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: projectByName.id,
          workspaceName,
        },
      });
    }
  }, [projectByName?.id, workspaceName, navigate]);

  if (query.id) {
    return <Navigate to={`/$workspaceName/projects/${query.id}`} />;
  }

  if (!isPendingProjectByName && !projectByName) {
    return <NoData message="No project with this name" />;
  }

  if (!query.id || !query.name) {
    return <NoData message="No project params set" />;
  }

  return <Loader />;
};

export default RedirectProjects;
