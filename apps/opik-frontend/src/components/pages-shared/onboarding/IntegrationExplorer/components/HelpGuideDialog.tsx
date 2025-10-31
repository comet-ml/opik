import React from "react";
import { ExternalLink, Book, PlayIcon } from "lucide-react";
import imageTutorialUrl from "/images/tutorial-placeholder.png";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Separator } from "@/components/ui/separator";
import usePluginsStore from "@/store/PluginsStore";
import PlayButton from "@/icons/play-button.svg?react";
import HelpLinks, { VIDEO_TUTORIAL_LINK } from "./HelpLinks";
import { buildDocsUrl } from "@/lib/utils";

type HelpGuideDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const HelpGuideDialog: React.FunctionComponent<HelpGuideDialogProps> = ({
  open,
  setOpen,
}) => {
  const InviteUsersForm = usePluginsStore((state) => state.InviteUsersForm);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
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
              className="inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
            >
              check our docs
              <ExternalLink className="size-3" />
            </a>
            .
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="rounded-lg border bg-background p-4">
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
                href={VIDEO_TUTORIAL_LINK}
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

            <div className="rounded-lg border bg-background p-4">
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
                  href={buildDocsUrl("/opik-university")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  Opik University
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/quickstart")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  Getting started with Opik
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/cookbook/overview")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  Opik&apos;s open-source collection of cookbooks
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/tracing/integrations/overview")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  Integrate Opik with your LLM application
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/tracing/log_agents")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  Track agent execution
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/faq")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  Read our FAQ
                  <ExternalLink className="size-4" />
                </a>
              </div>
            </div>
          </div>

          {InviteUsersForm ? <InviteUsersForm /> : null}

          <Separator className="my-6" />

          <HelpLinks
            onCloseParentDialog={() => setOpen(false)}
            title="Not ready to integrate yet?"
            description="Explore Opik by testing things out in the playground or browsing our Demo project. No setup required."
          >
            <HelpLinks.Playground />
            <HelpLinks.DemoProject />
            <HelpLinks.Slack />
          </HelpLinks>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default HelpGuideDialog;
