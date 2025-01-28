import React, { useState } from "react";
import { Book, GraduationCap } from "lucide-react";
import logFirstTraceImageUrl from "/images/log-first-trace.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import QuickstartDialog from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";

const NoTracesPage = () => {
  const [openQuickstart, setOpenQuickstart] = useState(false);

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
          <Button onClick={() => setOpenQuickstart(true)}>
            <GraduationCap className="mr-2 size-4" />
            Explore Quickstart guide
          </Button>
        </div>
      </div>

      <QuickstartDialog open={openQuickstart} setOpen={setOpenQuickstart} />
    </div>
  );
};

export default NoTracesPage;
