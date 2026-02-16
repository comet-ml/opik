import { Navigate, Outlet } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";

const ExperimentsGuard: React.FC<{ canViewExperiments?: boolean }> = ({
  canViewExperiments,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
