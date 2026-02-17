import { Navigate, Outlet } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";

const PageGuard: React.FC<{ canViewPage?: boolean }> = ({ canViewPage }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  if (canViewPage === undefined) {
    return <Loader />;
  }

  if (!canViewPage) {
    return (
      <Navigate to="/$workspaceName/home" params={{ workspaceName }} replace />
    );
  }

  return <Outlet />;
};

export default PageGuard;
