import noop from "lodash/noop";
import { Navigate, useParams } from "@tanstack/react-router";
import usePluginsStore from "@/store/PluginsStore";

const ProjectHomePage = () => {
  const { workspaceName, projectId } = useParams({
    strict: false,
  }) as { workspaceName: string; projectId: string };

  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);

  if (!AssistantSidebar) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/logs"
        params={{ workspaceName, projectId }}
        replace
      />
    );
  }

  return (
    <div className="-mx-6 flex h-full">
      <AssistantSidebar surface="page" onWidthChange={noop} />
    </div>
  );
};

export default ProjectHomePage;
