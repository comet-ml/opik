import React from "react";
import { ExternalLink } from "lucide-react";
import { Link } from "@tanstack/react-router";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useAppStore from "@/store/AppStore";
import { WORKSPACE_PREFERENCES_QUERY_PARAMS } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/constants";
import { WORKSPACE_PREFERENCE_TYPE } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/types";

type TruncationConfigPopoverProps = {
  children: React.ReactNode;
  message: string;
};

const TruncationConfigPopover: React.FC<TruncationConfigPopoverProps> = ({
  children,
  message,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <HoverCard openDelay={200}>
      <HoverCardTrigger asChild>{children}</HoverCardTrigger>
      <HoverCardContent
        className="w-80 pb-1"
        align="start"
        onClick={(event) => event.stopPropagation()}
      >
        <div>
          <p className="text-xs text-muted-foreground">{message}</p>
          <Separator className="my-2" />
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

export default TruncationConfigPopover;
