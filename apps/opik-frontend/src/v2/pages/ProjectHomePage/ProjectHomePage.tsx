import { Navigate, useParams } from "@tanstack/react-router";

const ProjectHomePage = () => {
  const { workspaceName, projectId } = useParams({
    strict: false,
  }) as { workspaceName: string; projectId: string };

  return (
    <Navigate
      to="/$workspaceName/projects/$projectId/logs"
      params={{ workspaceName, projectId }}
      replace
    />
  );
};

export default ProjectHomePage;
