import React, { useMemo } from "react";
import { Book, GraduationCap } from "lucide-react";
import noDataTracesImageUrl from "/images/no-data-traces.png";
import noDataSpansImageUrl from "/images/no-data-spans.png";
import noDataMetricsImageUrl from "/images/no-data-metrics.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

type NoTracesPageProps = {
  type?: TRACE_DATA_TYPE | "metrics";
};

const NoTracesPage: React.FC<NoTracesPageProps> = ({
  type = TRACE_DATA_TYPE.traces,
}) => {
  const { open: openQuickstart } = useOpenQuickStartDialog();

  const imageUrl = useMemo(() => {
    switch (type) {
      case TRACE_DATA_TYPE.traces:
        return noDataTracesImageUrl;
      case TRACE_DATA_TYPE.spans:
        return noDataSpansImageUrl;
      case "metrics":
        return noDataMetricsImageUrl;
      default:
        return noDataTracesImageUrl;
    }
  }, [type]);

  return (
    <NoDataPage
      title="Log your first trace"
      description="Logging traces helps you understand the flow of your application and identify specific points in your application that may be causing issues."
      imageUrl={imageUrl}
      height={188}
      className="px-6"
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
          <Button onClick={openQuickstart}>
            <GraduationCap className="mr-2 size-4" />
            Explore Quickstart guide
          </Button>
        </>
      }
    ></NoDataPage>
  );
};

export default NoTracesPage;
