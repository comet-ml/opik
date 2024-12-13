import useAppStore from "@/store/AppStore";
import { Navigate, Outlet, useMatchRoute } from "@tanstack/react-router";

const WorkspacePage = () => {
  const activeWorkspaceName = useAppStore((state) => state.activeWorkspaceName);
  const matchRoute = useMatchRoute();

  const isRootPage = matchRoute({ to: "/$workspaceName" });

  if (isRootPage) {
    return (
      <Navigate
        to="/$workspaceName/home"
        params={{ workspaceName: activeWorkspaceName }}
      />
    );
  }

  return <Outlet />;
};

export default WorkspacePage;
