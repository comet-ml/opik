import { Link, useParams } from "@tanstack/react-router";
import { ArrowUpRight } from "lucide-react";
import { buttonVariants } from "@/ui/button";
import { useLoggedInUserName } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import usePluginsStore from "@/store/PluginsStore";
import DailyBriefingSection from "./DailyBriefingSection";
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

  return (
    <div className="mx-auto flex size-full max-w-[720px] flex-col gap-6 py-6">
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
