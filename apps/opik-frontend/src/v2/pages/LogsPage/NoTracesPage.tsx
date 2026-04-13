import React from "react";
import { Workflow, Link } from "lucide-react";
import { buildDocsUrl } from "@/lib/utils";
import { useOpenQuickStartDialog } from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import DataTableEmptyState from "@/shared/DataTableEmptyState/DataTableEmptyState";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

type NoTracesPageProps = {
  type?: TRACE_DATA_TYPE;
};

const EMPTY_STATE_CONFIG = {
  [TRACE_DATA_TYPE.traces]: {
    icon: Workflow,
    title: "Log your first trace",
    description:
      "Capture traces to see how your agent thinks, responds, and uses tools.",
    docsPath: "/tracing/log_traces",
  },
  [TRACE_DATA_TYPE.spans]: {
    icon: Link,
    title: "Log your first span",
    description:
      "Capture spans to see the individual steps within your traces.",
    docsPath: "/tracing/log_traces",
  },
};

const NoTracesPage: React.FC<NoTracesPageProps> = ({
  type = TRACE_DATA_TYPE.traces,
}) => {
  const { open: openQuickstart } = useOpenQuickStartDialog();
  const config = EMPTY_STATE_CONFIG[type];

  return (
    <DataTableEmptyState
      icon={config.icon}
      title={config.title}
      description={config.description}
      docsUrl={buildDocsUrl(config.docsPath)}
      onQuickstartClick={openQuickstart}
    />
  );
};

export default NoTracesPage;
