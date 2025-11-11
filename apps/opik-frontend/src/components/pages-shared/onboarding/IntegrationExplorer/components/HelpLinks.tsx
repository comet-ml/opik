import React, { createContext, useContext } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Blocks, MonitorPlay, MousePointerClick } from "lucide-react";
import useAppStore from "@/store/AppStore";
import Slack from "@/icons/slack.svg?react";
import { Link } from "@tanstack/react-router";
import usePluginsStore from "@/store/PluginsStore";

export const VIDEO_TUTORIAL_LINK =
  "https://www.youtube.com/watch?v=h1XK-dMtUJI";
export const SLACK_LINK = "http://chat.comet.com";

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
  title: string;
  description: string;
};

type HelpLinksComponent = React.FC<HelpLinksProps> & {
  InviteDev: React.FC;
  Slack: React.FC;
  WatchTutorial: React.FC;
  Playground: React.FC;
  DemoProject: React.FC;
};

const HelpLinks: HelpLinksComponent = ({
  className,
  buttonsContainerClassName,
  children,
  title,
  description,
  onCloseParentDialog,
}) => {
  return (
    <HelpLinksContext.Provider
      value={{
        onCloseParentDialog,
      }}
    >
      <div className={cn(className)}>
        <h3 className="comet-title-s mb-2">{title}</h3>
        <p className="comet-body-s mb-4 py-2 text-muted-slate">{description}</p>

        <div
          className={cn("flex gap-2.5 flex-wrap", buttonsContainerClassName)}
        >
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

  const InviteDevButtonComponent = usePluginsStore(
    (state) => state.InviteDevButton,
  );

  if (!InviteDevButtonComponent) {
    return null;
  }

  return <InviteDevButtonComponent onClick={onCloseParentDialog} />;
};
InviteDevButton.displayName = "HelpLinks.InviteDev";

const SlackButton: React.FC = () => {
  return (
    <Button
      className="flex-1"
      variant="outline"
      asChild
      id="help-links-slack"
      data-fs-element="HelpLinksSlack"
    >
      <a href={SLACK_LINK} target="_blank" rel="noopener noreferrer">
        <Slack className="mr-2 size-4" />
        <span>Get help in Slack</span>
      </a>
    </Button>
  );
};
SlackButton.displayName = "HelpLinks.Slack";

const PlaygroundButton: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <Button
      variant="outline"
      className="flex-1"
      asChild
      id="help-links-playground"
      data-fs-element="HelpLinksPlayground"
    >
      <Link to={"/$workspaceName/playground"} params={{ workspaceName }}>
        <Blocks className="mr-2 size-4" />
        Try our Playground
      </Link>
    </Button>
  );
};
PlaygroundButton.displayName = "HelpLinks.Playground";

const DemoProjectButton: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <Button
      variant="outline"
      className="flex-1"
      asChild
      id="help-links-demo-project"
      data-fs-element="HelpLinksDemoProject"
    >
      <Link
        to={"/$workspaceName/projects"}
        params={{
          workspaceName,
        }}
      >
        <MousePointerClick className="mr-2 size-4" />
        Explore our Demo project
      </Link>
    </Button>
  );
};
DemoProjectButton.displayName = "HelpLinks.DemoProject";

const WatchTutorialButton: React.FC = () => {
  return (
    <Button
      className="flex-1"
      variant="outline"
      asChild
      id="help-links-watch-tutorial"
      data-fs-element="HelpLinksWatchTutorial"
    >
      <a href={VIDEO_TUTORIAL_LINK} target="_blank" rel="noopener noreferrer">
        <MonitorPlay className="mr-2 size-4" />
        <span>Watch our tutorial</span>
      </a>
    </Button>
  );
};
WatchTutorialButton.displayName = "HelpLinks.WatchTutorial";

HelpLinks.InviteDev = InviteDevButton;
HelpLinks.Slack = SlackButton;
HelpLinks.WatchTutorial = WatchTutorialButton;
HelpLinks.Playground = PlaygroundButton;
HelpLinks.DemoProject = DemoProjectButton;

HelpLinks.displayName = "HelpLinks";

export default HelpLinks;
