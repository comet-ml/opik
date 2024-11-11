import React from "react";
import { Book, GraduationCap } from "lucide-react";
import logFirstTraceImageUrl from "/images/log-first-trace.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

const NoTracesPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="min-w-[340px] py-6">
      <div className="flex flex-col items-center rounded-md border bg-white px-6 pb-6 pt-20">
        <h2 className="comet-title-m">Log your first trace</h2>
        <div className="comet-body-s max-w-[570px] px-4 pb-8 pt-4 text-center text-muted-slate">
          Logging traces helps you understand the flow of your application and
          identify specific points in your application that may be causing
          issues.
        </div>
        <img
          className="max-h-[400px] object-cover"
          src={logFirstTraceImageUrl}
          alt="image first trace"
        />
        <div className="flex flex-wrap justify-center gap-2 pt-8">
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/tracing/log_traces")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              Read documentation
            </a>
          </Button>
          <Link to="/$workspaceName/quickstart" params={{ workspaceName }}>
            <Button>
              <GraduationCap className="mr-2 size-4" />
              Explore Quickstart guide
            </Button>
          </Link>
        </div>
      </div>
    </div>
  );
};

export default NoTracesPage;
