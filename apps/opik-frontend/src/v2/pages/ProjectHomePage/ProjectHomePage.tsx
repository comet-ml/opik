import { useNavigate, useParams } from "@tanstack/react-router";
import { ArrowUpRight } from "lucide-react";
import { Button } from "@/ui/button";
import { useLoggedInUserName } from "@/store/AppStore";
import RecentActivitySection from "./RecentActivitySection";

const ProjectHomePage = () => {
  const { workspaceName, projectId } = useParams({
    strict: false,
  }) as { workspaceName: string; projectId: string };
  const navigate = useNavigate();
  const userName = useLoggedInUserName();

  return (
    <div className="mx-auto flex size-full max-w-[720px] flex-col py-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="comet-body-accented">
          Hi{userName ? `, ${userName}` : ""}
        </h1>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="xs"
            onClick={() =>
              navigate({
                to: "/$workspaceName/projects/$projectId/logs",
                params: { workspaceName, projectId },
              })
            }
          >
            View logs
            <ArrowUpRight className="ml-1 size-3.5" />
          </Button>
          <Button
            variant="outline"
            size="xs"
            onClick={() =>
              navigate({
                to: "/$workspaceName/projects/$projectId/dashboards",
                params: { workspaceName, projectId },
              })
            }
          >
            View dashboards
            <ArrowUpRight className="ml-1 size-3.5" />
          </Button>
        </div>
      </div>

      <RecentActivitySection />
    </div>
  );
};

export default ProjectHomePage;
