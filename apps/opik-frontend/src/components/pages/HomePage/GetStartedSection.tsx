import React from "react";
import { ArrowRight, MessageCircle, MousePointer, X } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";

import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import { buildDocsUrl } from "@/lib/utils";

const GetStartedSection = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [hide, setHide] = useLocalStorageState<boolean>(
    "home-get-started-section",
    {
      defaultValue: false,
    },
  );

  if (hide) return null;

  return (
    <div>
      <div className="flex items-center justify-between gap-8 pb-4 pt-2">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-accented truncate break-words">
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
        <Alert className="mt-4">
          <MousePointer className="size-4" />
          <AlertTitle>Explore our demo project</AlertTitle>
          <AlertDescription>
            Browse our curated examples to draw inspiration for your own LLM
            projects.
          </AlertDescription>
          <Link
            to="/$workspaceName/redirect/projects"
            params={{ workspaceName }}
            search={{ name: DEMO_PROJECT_NAME }}
          >
            <Button variant="ghost" size="sm" className="-ml-2">
              Go to demo project
              <ArrowRight className="ml-1 size-4 shrink-0" />
            </Button>
          </Link>
        </Alert>
        <Alert className="mt-4">
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
        <Alert className="mt-4">
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
    </div>
  );
};

export default GetStartedSection;
