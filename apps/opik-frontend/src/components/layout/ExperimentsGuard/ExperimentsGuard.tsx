import { Navigate, Outlet } from "@tanstack/react-router";
import useUserPermission from "@/plugins/comet/useUserPermission";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";

const ExperimentsGuard = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { canViewExperiments } = useUserPermission();

  if (canViewExperiments === undefined) {
    return <Loader />;
  }

  if (!canViewExperiments) {
    return (
      <Navigate to="/$workspaceName/home" params={{ workspaceName }} replace />
    );
  }

  return <Outlet />;
};

export default ExperimentsGuard;
