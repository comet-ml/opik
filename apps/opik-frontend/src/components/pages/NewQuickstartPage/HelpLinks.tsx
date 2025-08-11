import React, { createContext, useContext } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Monitor, UserPlus } from "lucide-react";
import useAppStore from "@/store/AppStore";
import { buildUrl } from "@/plugins/comet/utils";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useUser from "@/plugins/comet/useUser";
import Slack from "@/icons/slack.svg?react";

const TUTORIAL_LINK = "https://www.youtube.com/";
const SLACK_LINK = "http://chat.comet.com";

type HelpLinksContextValue = {
  onCloseParentDialog?: () => void;
};

const HelpLinksContext = createContext<HelpLinksContextValue | undefined>(
  undefined,
);

type HelpLinksProps = {
  className?: string;
  children?: React.ReactNode;
  buttonsContainerClassName?: string;
  onCloseParentDialog?: () => void;
};

type HelpLinksComponent = React.FC<HelpLinksProps> & {
  InviteDev: React.FC;
  Slack: React.FC;
  WatchTutorial: React.FC;
};

const HelpLinks: HelpLinksComponent = ({
  className,
  buttonsContainerClassName,
  children,
  onCloseParentDialog,
}) => {
  return (
    <HelpLinksContext.Provider
      value={{
        onCloseParentDialog,
      }}
    >
      <div className={cn(className)}>
        <h3 className="comet-title-s mb-2">Not ready to integrate yet?</h3>
        <p className="comet-body-s mb-4 py-2 text-muted-slate">
          Explore Opik by testing things out in the playground or browsing our
          Demo project. No setup required.
        </p>

        <div className={cn("flex gap-2.5", buttonsContainerClassName)}>
          {children}
        </div>
      </div>
    </HelpLinksContext.Provider>
  );
};

const useHelpLinks = (): HelpLinksContextValue => {
  const ctx = useContext(HelpLinksContext);
  if (!ctx) {
    throw new Error("HelpLinks.* must be used within HelpLinks root");
  }
  return ctx;
};

const InviteDevButton: React.FC = () => {
  const { onCloseParentDialog } = useHelpLinks();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (workspace) => workspace.workspaceName === workspaceName,
  );

  if (!user) {
    return null;
  }

  return (
    <Button
      className="w-full"
      variant="outline"
      onClick={onCloseParentDialog}
      asChild
    >
      <a
        href={buildUrl(
          "account-settings/workspaces",
          workspaceName,
          `&initialInviteId=${workspace?.workspaceId}`,
        )}
      >
        <UserPlus className="mr-2 size-4" />
        <span>Invite a developer</span>
      </a>
    </Button>
  );
};
InviteDevButton.displayName = "HelpLinks.InviteDev";

const SlackButton: React.FC = () => {
  return (
    <Button className="w-full" variant="outline" asChild>
      <a href={SLACK_LINK}>
        <Slack className="mr-2 size-4" />
        <span>Get help in Slack</span>
      </a>
    </Button>
  );
};
SlackButton.displayName = "HelpLinks.Slack";

const WatchTutorialButton: React.FC = () => {
  const handleClick = () => {
    window.open(TUTORIAL_LINK, "_blank");
  };
  return (
    <Button className="w-full" variant="outline" onClick={handleClick}>
      <Monitor className="mr-2 size-4" />
      <span>Watch our tutorial</span>
    </Button>
  );
};
WatchTutorialButton.displayName = "HelpLinks.WatchTutorial";

HelpLinks.InviteDev = InviteDevButton;
HelpLinks.Slack = SlackButton;
HelpLinks.WatchTutorial = WatchTutorialButton;

HelpLinks.displayName = "HelpLinks";

export default HelpLinks;
