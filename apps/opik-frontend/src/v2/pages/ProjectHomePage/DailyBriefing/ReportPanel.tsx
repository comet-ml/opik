import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { ChevronsRight, Lightbulb } from "lucide-react";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { OllieReport, RecommendedAction } from "@/types/ollie-reports";
import { formatRelativeDateTime } from "@/lib/date";

type ReportPanelProps = {
  report: OllieReport | null;
  onClose: () => void;
  onStartConversation: (action: RecommendedAction) => void;
};

export default function ReportPanel({
  report,
  onClose,
  onStartConversation,
}: ReportPanelProps) {
  const actions = report?.recommended_actions ?? [];

  return (
    <ResizableSidePanel
      panelId="report-panel"
      open={report != null}
      onClose={onClose}
      header={
        <div className="flex w-full items-center gap-2 px-4 py-3">
          <button
            className="text-muted-foreground hover:text-foreground"
            onClick={onClose}
          >
            <ChevronsRight className="size-4" />
          </button>
          <h3 className="comet-body-s-accented truncate">
            {report
              ? `Daily briefing: ${formatRelativeDateTime(report.created_at)}`
              : ""}
          </h3>
        </div>
      }
    >
      <div className="size-full overflow-y-auto p-6">
        {actions.length > 0 && (
          <div className="mb-6 flex gap-4">
            {actions.map((action, i) => (
              <button
                key={i}
                className="flex flex-1 flex-col gap-1 rounded-md border p-3 text-left hover:border-primary hover:bg-primary/5"
                onClick={() => onStartConversation(action)}
              >
                <div className="flex items-center gap-1.5">
                  <Lightbulb className="size-3 shrink-0 text-primary" />
                  <span className="comet-body-s-accented">{action.name}</span>
                </div>
                <p className="comet-body-xs text-muted-slate">
                  {action.description}
                </p>
              </button>
            ))}
          </div>
        )}

        {report?.content ? (
          <MarkdownPreview className="prose-sm">
            {report.content}
          </MarkdownPreview>
        ) : (
          <p className="text-sm text-muted-foreground">No content available.</p>
        )}
      </div>
    </ResizableSidePanel>
  );
}
