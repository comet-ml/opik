import React from "react";
import {
  ExternalLink,
  Book,
  UserPlus,
  Blocks,
  MousePointerClick,
  PlayIcon,
} from "lucide-react";
import imageTutorialUrl from "/images/tutorial-placeholder.png";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Separator } from "@/components/ui/separator";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";
import usePluginsStore from "@/store/PluginsStore";
import PlayButton from "@/icons/play-button.svg?react";

type HelpGuideDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const HelpGuideDialog: React.FunctionComponent<HelpGuideDialogProps> = ({
  open,
  setOpen,
}) => {
  const InviteUsersForm = usePluginsStore((state) => state.InviteUsersForm);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: demoProject } = useDemoProject({ workspaceName });
  const demoProjectId = demoProject?.id || "";

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[790px] gap-2">
        <DialogHeader>
          <DialogTitle>Help guide</DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody>
          <div className="comet-body-s mb-3 pb-2 text-muted-slate">
            Need help getting started? Find useful resources here, or{" "}
            <a
              href="https://www.comet.com/docs/opik/quickstart"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline"
            >
              check our docs
              <ExternalLink className="size-3" />
            </a>
            .
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="rounded-lg border bg-card p-4">
              <div className="mb-4 flex flex-col items-start gap-1.5">
                <div className="flex items-center gap-2">
                  <PlayIcon className="size-4 text-muted-slate" />
                  <div className="comet-body-s-accented">
                    Watch our guided tutorial
                  </div>
                </div>

                <div className="comet-body-s text-muted-slate">
                  Watch a short video to guide you through setup and key
                  features.
                </div>
              </div>

              <a
                href="https://www.youtube.com/watch?v=h1XK-dMtUJI"
                target="_blank"
                rel="noopener noreferrer"
                className="group relative flex aspect-video cursor-pointer items-center justify-center after:absolute after:inset-0 after:rounded-lg after:bg-black/20 after:opacity-0 after:transition-opacity after:hover:opacity-50"
              >
                <img
                  src={imageTutorialUrl}
                  alt="Comet tutorial"
                  className="size-full object-cover"
                />
                <PlayButton className="absolute left-1/2 top-1/2 size-10 -translate-x-1/2 -translate-y-1/2 opacity-25 transition-opacity group-hover:opacity-80" />
              </a>
            </div>

            <div className="rounded-lg border bg-card p-4">
              <div className="mb-2 flex flex-col items-start gap-1.5">
                <div className="flex items-center gap-2">
                  <Book className="size-4 text-muted-slate" />
                  <div className="comet-body-s-accented">
                    Explore our documentation
                  </div>
                </div>
                <div className="comet-body-s text-muted-slate">
                  Check out our docs and helpful guides to get started.
                </div>
              </div>

              <div className="space-y-2">
                <a
                  href="https://www.comet.com/docs/opik/quickstart"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline"
                >
                  Getting started with Opik
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://www.comet.com/docs/opik/cookbook/overview"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline"
                >
                  Opik&apos;s open-source collection of cookbooks
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://www.comet.com/docs/opik/tracing/integrations/overview"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline"
                >
                  Integrate Opik with your LLM application
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://www.comet.com/docs/opik/tracing/log_agents"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline"
                >
                  Track agent execution
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://www.comet.com/docs/opik/faq"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline"
                >
                  Read our FAQ
                  <ExternalLink className="size-4" />
                </a>
              </div>
            </div>
          </div>

          <div className="rounded-lg border bg-card p-4">
            <div className="mb-4 flex flex-col items-start gap-1.5">
              <div className="flex items-center gap-2">
                <UserPlus className="size-4 text-muted-slate" />
                <div className="comet-body-s-accented">
                  Invite a team member
                </div>
              </div>
              <div className="comet-body-s text-muted-slate">
                Invite teammates by email or username to join your workspace and
                help with setup. Use commas to invite multiple people at once.
              </div>
            </div>

            {InviteUsersForm ? <InviteUsersForm /> : null}
          </div>

          <Separator className="my-6" />

          <div>
            <h3 className="comet-title-s mb-2">Not ready to integrate yet?</h3>
            <p className="comet-body-s mb-4 py-2 text-muted-slate">
              Explore Opik by testing things out in the playground or browsing
              our Demo project. No setup required.
            </p>

            <div className="mt-4 flex gap-3">
              <Button variant="outline" className="flex-1" asChild>
                <Link
                  to={"/$workspaceName/playground"}
                  params={{ workspaceName }}
                >
                  <Blocks className="mr-2 size-4" />
                  Try our Playground
                </Link>
              </Button>
              {demoProjectId && (
                <Button
                  variant="outline"
                  className="flex-1"
                  asChild
                  disabled={!demoProjectId}
                >
                  <Link
                    to={"/$workspaceName/projects/$projectId/traces"}
                    params={{
                      workspaceName,
                      projectId: demoProjectId,
                    }}
                  >
                    <MousePointerClick className="mr-2 size-4" />
                    Explore our Demo project
                  </Link>
                </Button>
              )}
            </div>
          </div>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default HelpGuideDialog;
