import { Link, Navigate, useParams } from "@tanstack/react-router";
import { ArrowUpRight } from "lucide-react";
import { buttonVariants } from "@/ui/button";
import { useLoggedInUserName } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import usePluginsStore from "@/store/PluginsStore";
import DailyBriefingSection from "./DailyBriefing/DailyBriefingSection";
import RecentActivitySection from "./RecentActivitySection";

const ProjectHomePage = () => {
  const { workspaceName, projectId } = useParams({
    strict: false,
  }) as { workspaceName: string; projectId: string };
  const userName = useLoggedInUserName();
  const {
    permissions: { canViewDashboards },
  } = usePermissions();
  const isOllieAvailable = !!usePluginsStore((state) => state.AssistantSidebar);
  const projectHomepageEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PROJECT_HOMEPAGE_ENABLED,
  );

  if (!projectHomepageEnabled) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/logs"
        params={{ workspaceName, projectId }}
        replace
      />
    );
  }

  return (
    <div className="mx-auto flex size-full max-w-[720px] flex-col gap-3 py-6">
      <div className="flex items-center justify-between">
        <h1 className="comet-body-accented">
          Hi{userName ? `, ${userName}` : ""}
        </h1>
        <div className="flex gap-2">
          <Link
            to="/$workspaceName/projects/$projectId/logs"
            params={{ workspaceName, projectId }}
            className={buttonVariants({ variant: "outline", size: "xs" })}
          >
            View logs
            <ArrowUpRight className="ml-1 size-3.5" />
          </Link>
          {canViewDashboards && (
            <Link
              to="/$workspaceName/projects/$projectId/dashboards"
              params={{ workspaceName, projectId }}
              className={buttonVariants({ variant: "outline", size: "xs" })}
            >
              View dashboards
              <ArrowUpRight className="ml-1 size-3.5" />
            </Link>
          )}
        </div>
      </div>

      {isOllieAvailable && <DailyBriefingSection />}
      <RecentActivitySection />
    </div>
  );
};

export default ProjectHomePage;
