import useAppStore from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { Navigate } from "@tanstack/react-router";

const GetStartedPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const GetStartedPage = usePluginsStore((state) => state.GetStartedPage);

  if (GetStartedPage) {
    return <GetStartedPage />;
  }

  return (
    <Navigate to={"/$workspaceName/projects"} params={{ workspaceName }} />
  );
};

export default GetStartedPage;
