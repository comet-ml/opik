import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkBreaks from "remark-breaks";
import { ChevronsRight } from "lucide-react";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { OllieReport } from "@/types/ollie-reports";
import { formatRelativeDateTime } from "@/lib/date";

type ReportPanelProps = {
  report: OllieReport | null;
  onClose: () => void;
};

export default function ReportPanel({ report, onClose }: ReportPanelProps) {
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
        {report?.content ? (
          <ReactMarkdown
            className="comet-markdown prose dark:prose-invert"
            remarkPlugins={[remarkBreaks, remarkGfm]}
          >
            {report.content}
          </ReactMarkdown>
        ) : (
          <p className="text-sm text-muted-foreground">No content available.</p>
        )}
      </div>
    </ResizableSidePanel>
  );
}
