import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Book, GraduationCap } from "lucide-react";
import { useQueryParam } from "use-query-params";
import noDataTracesImageUrl from "/images/no-data-traces.png";
import noDataSpansImageUrl from "/images/no-data-spans.png";
import noDataMetricsImageUrl from "/images/no-data-metrics.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

const NoTracesPage = () => {
  const { t } = useTranslation();
  const { open: openQuickstart } = useOpenQuickStartDialog();
  const [type = TRACE_DATA_TYPE.traces] = useQueryParam("type");

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
      title={t("traces.emptyState.title")}
      description={t("traces.emptyState.description")}
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
              {t("traces.emptyState.readDocumentation")}
            </a>
          </Button>
          <Button onClick={openQuickstart}>
            <GraduationCap className="mr-2 size-4" />
            {t("traces.emptyState.exploreQuickstart")}
          </Button>
        </>
      }
    ></NoDataPage>
  );
};

export default NoTracesPage;
