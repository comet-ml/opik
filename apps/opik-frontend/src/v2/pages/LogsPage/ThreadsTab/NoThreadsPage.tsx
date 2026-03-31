import React from "react";
import { MessagesSquare } from "lucide-react";
import { buildDocsUrl } from "@/lib/utils";
import { useOpenQuickStartDialog } from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import DataTableEmptyState from "@/shared/DataTableEmptyState/DataTableEmptyState";

const NoThreadsPage = () => {
  const { open: openQuickstart } = useOpenQuickStartDialog();

  return (
    <DataTableEmptyState
      icon={MessagesSquare}
      title="Log your first thread"
      description="Threads allow you to group traces together to help you evaluate your LLM model outputs in their specific context."
      docsUrl={buildDocsUrl("/tracing/log_chat_conversations")}
      onQuickstartClick={openQuickstart}
    />
  );
};

export default NoThreadsPage;
