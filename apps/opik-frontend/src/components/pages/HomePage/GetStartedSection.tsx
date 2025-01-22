import React, { useState } from "react";
import { ArrowRight, MessageCircle, MousePointer, X } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";

import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";
import { buildDocsUrl } from "@/lib/utils";
import SideDialog from "@/components/shared/SideDialog/SideDialog";
import FrameworkIntegrations from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrations";

const GetStartedSection = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [hide, setHide] = useLocalStorageState<boolean>(
    "home-get-started-section",
    {
      defaultValue: false,
    },
  );

  const [openLogTraceDialog, setOpenLogTraceDialog] = useState(false);

  const { data: project, isPending } = useDemoProject(
    { workspaceName },
    {
      enabled: !hide,
      placeholderData: keepPreviousData,
    },
  );

  if (hide || isPending) return null;

  return (
    <div>
      <div className="flex items-center justify-between gap-8 pb-4 pt-2">
        <div className="flex items-center gap-2">
          <h2
            onClick={() => setOpenLogTraceDialog(true)}
            className="comet-body-accented truncate break-words"
          >
            Get started with Opik
          </h2>
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="minimal"
            size="icon-xs"
            onClick={() => setHide(true)}
          >
            <X className="size-4" />
          </Button>
        </div>
      </div>
      <div className="flex gap-x-4">
        {project && (
          <Alert>
            <MousePointer className="size-4" />
            <AlertTitle>Explore our demo project</AlertTitle>
            <AlertDescription>
              Browse our curated examples to draw inspiration for your own LLM
              projects.
            </AlertDescription>
            <Link
              to="/$workspaceName/projects/$projectId"
              params={{ workspaceName, projectId: project.id }}
            >
              <Button variant="ghost" size="sm" className="-ml-2">
                Go to demo project
                <ArrowRight className="ml-1 size-4 shrink-0" />
              </Button>
            </Link>
          </Alert>
        )}
        <Alert>
          <MessageCircle className="size-4" />
          <AlertTitle>Log a trace</AlertTitle>
          <AlertDescription>
            The first step in integrating Opik with your codebase is to track
            your LLM calls.
          </AlertDescription>
          <Button variant="ghost" size="sm" asChild>
            <a
              href={buildDocsUrl("/tracing/log_traces")}
              target="_blank"
              rel="noreferrer"
            >
              Start logging traces
              <ArrowRight className="ml-1 size-4 shrink-0" />
            </a>
          </Button>
        </Alert>
        <Alert>
          <MessageCircle className="size-4" />
          <AlertTitle>Run an experiment</AlertTitle>
          <AlertDescription>
            An experiment is a single evaluation of your LLM application.
          </AlertDescription>
          <Button variant="ghost" size="sm" asChild>
            <a
              href={buildDocsUrl("/evaluation/evaluate_your_llm")}
              target="_blank"
              rel="noreferrer"
            >
              Start running experiments
              <ArrowRight className="ml-1 size-4 shrink-0" />
            </a>
          </Button>
        </Alert>
      </div>
      <Separator className="my-6" />

      <SideDialog open={openLogTraceDialog} setOpen={setOpenLogTraceDialog}>
        <div className="flex w-full min-w-fit flex-col pb-12">
          <div className="pb-8">
            <h1 className="comet-title-l text-center">Log a trace</h1>
            <div className="comet-body-s text-muted-slate m-auto mt-4 w-[468px] self-center text-center">
              Select a framework and follow the instructions to integrate Comet
              with your code, or explore our ready-to-run examples on the right
            </div>
          </div>
          <FrameworkIntegrations />
        </div>
      </SideDialog>
    </div>
  );
};

export default GetStartedSection;
