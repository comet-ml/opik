import { Outlet, useRouter } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import { Button } from "@/components/ui/button";

interface NoAccessPageGuardProps {
  canViewPage?: boolean;
  resourceName?: string;
}

const NoAccessPageGuard: React.FC<NoAccessPageGuardProps> = ({
  canViewPage,
  resourceName = "this resource",
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const router = useRouter();

  if (canViewPage === undefined) {
    return <Loader />;
  }

  if (!canViewPage) {
    const routerState = router.state;
    const canGoBack =
      routerState.location.state?.key !== undefined &&
      globalThis.history.length > 1;

    const handleGoHome = () => {
      router.navigate({
        to: "/$workspaceName/home",
        params: { workspaceName },
      });
    };

    const handleGoBack = () => {
      router.history.back();
    };

    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">403</div>}
        title="Access denied"
        message={`You don't have permissions to view ${resourceName} in this workspace.`}
      >
        <div className="flex gap-2 pt-5">
          <Button onClick={handleGoHome}>Back to Home</Button>
          {canGoBack && (
            <Button variant="outline" onClick={handleGoBack}>
              Go back to previous page
            </Button>
          )}
        </div>
      </NoData>
    );
  }

  return <Outlet />;
};

export default NoAccessPageGuard;
