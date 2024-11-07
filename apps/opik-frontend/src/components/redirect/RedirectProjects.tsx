import { useEffect, useMemo } from "react";
import Loader from "@/components/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { useNavigate } from "@tanstack/react-router";
import useProjectByName from "@/api/projects/useProjectByName";

const RedirectProjects = () => {
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const projectById = query?.id;

  const queryKey = useMemo(() => {
    if (query.id) {
      return "id";
    }

    if (query.name) {
      return "name";
    }

    return null;
  }, [query.id, query.name]);

  // redirecting by name
  const { data: projectByName, isPending: isPendingProjectByName } =
    useProjectByName(
      { projectName: query.name || "" },
      { enabled: !!query.name && queryKey === "name" },
    );
  // <--------------------------------------------

  const projectIdToRedirect = useMemo(() => {
    if (projectById && queryKey === "id") {
      return projectById;
    }

    if (projectByName && queryKey === "name") {
      return projectByName?.id;
    }

    return null;
  }, [projectByName, projectById]);

  useEffect(() => {
    if (projectIdToRedirect) {
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: projectIdToRedirect,
          workspaceName,
        },
      });
    }
  }, [projectIdToRedirect, workspaceName]);

  if (queryKey === "name" && !isPendingProjectByName && !projectByName) {
    return <div>Not Found</div>;
  }

  return <Loader />;
};

export default RedirectProjects;
