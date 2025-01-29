import React, { useMemo, useState } from "react";
import { Book, GraduationCap } from "lucide-react";
import { useQueryParam } from "use-query-params";
import noDataTracesImageUrl from "/images/no-data-traces.png";
import noDataSpansImageUrl from "/images/no-data-spans.png";
import noDataMetricsImageUrl from "/images/no-data-metrics.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import QuickstartDialog from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import NoDataTab from "@/components/pages/TracesPage/NoDataTab";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

const NoTracesPage = () => {
  const [openQuickstart, setOpenQuickstart] = useState(false);
  const [type = TRACE_DATA_TYPE.traces] = useQueryParam("type");

  const imageUrl = useMemo(() => {
    switch (type) {
      case TRACE_DATA_TYPE.traces:
        return noDataTracesImageUrl;
      case TRACE_DATA_TYPE.llm:
        return noDataSpansImageUrl;
      case "metrics":
        return noDataMetricsImageUrl;
      default:
        return noDataTracesImageUrl;
    }
  }, [type]);

  return (
    <NoDataTab
      title="Log your first trace"
      description="Logging traces helps you understand the flow of your application and identify specific points in your application that may be causing issues."
      imageUrl={imageUrl}
      buttons={
        <>
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
          <QuickstartDialog open={openQuickstart} setOpen={setOpenQuickstart} />
        </>
      }
    ></NoDataTab>
  );
};

export default NoTracesPage;
