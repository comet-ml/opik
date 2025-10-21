import React from "react";
import { ExternalLink, HelpCircle } from "lucide-react";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { WORKSPACE_PREFERENCES_QUERY_PARAMS } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/constants";
import { WORKSPACE_PREFERENCE_TYPE } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/types";

const TruncationDisabledWarning: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <HoverCard openDelay={200}>
      <HoverCardTrigger asChild>
        <div className="flex cursor-help items-center gap-1 text-xs text-muted-foreground">
          <span>Pagination limited</span>
          <HelpCircle className="size-3" />
        </div>
      </HoverCardTrigger>
      <HoverCardContent className="w-80" align="start">
        <div>
          <p className="text-xs text-muted-foreground">
            Pagination limited to 10 items. Enable truncation in preferences to
            view more items per page.
          </p>

          <Separator className="my-1" />
          <Button
            variant="link"
            className="w-full justify-start gap-1 px-2"
            asChild
          >
            <Link
              to="/$workspaceName/configuration"
              params={{ workspaceName }}
              search={{
                tab: "workspace-preferences",
                [WORKSPACE_PREFERENCES_QUERY_PARAMS.EDIT_PREFERENCE]:
                  WORKSPACE_PREFERENCE_TYPE.TRUNCATION_TOGGLE,
              }}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1"
            >
              <span>Manage table truncation</span>
              <ExternalLink className="size-3" />
            </Link>
          </Button>
        </div>
      </HoverCardContent>
    </HoverCard>
  );
};

export default TruncationDisabledWarning;
