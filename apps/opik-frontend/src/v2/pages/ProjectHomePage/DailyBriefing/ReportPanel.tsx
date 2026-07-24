import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { ChevronsRight, Lightbulb } from "lucide-react";
import { cn } from "@/lib/utils";
import { Separator } from "@/ui/separator";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { OllieReport, RecommendedAction } from "@/types/ollie-reports";
import { formatRelativeDateTime } from "@/lib/date";
import { ACCENT_CARD_STYLES } from "@/constants/accentCardStyles";

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
      <div className="flex size-full flex-col gap-2 overflow-y-auto p-4">
        {actions.length > 0 && (
          <div className="flex flex-col gap-3">
            <h4 className="comet-title-s">Action items</h4>
            <div className="flex gap-3">
              {actions.map((action, i) => {
                const style = ACCENT_CARD_STYLES[i % ACCENT_CARD_STYLES.length];
                return (
                  <button
                    key={action.name}
                    className={cn(
                      "flex flex-1 flex-col rounded-md border p-3 pb-2 text-left shadow-sm transition-colors",
                      style.card,
                    )}
                    onClick={() => onStartConversation(action)}
                  >
                    <div
                      className={cn(
                        "mb-2 flex size-5 items-center justify-center rounded-[6px]",
                        style.iconBg,
                      )}
                    >
                      <Lightbulb className="size-3 shrink-0 text-foreground-secondary dark:text-background" />
                    </div>
                    <span className="comet-body-s-accented">{action.name}</span>
                    <p className="comet-body-xs mt-px text-muted-slate">
                      {action.description}
                    </p>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {actions.length > 0 && report?.content && (
          <div className="py-2">
            <Separator />
          </div>
        )}

        {report?.content ? (
          <div className="flex flex-col gap-3">
            <h4 className="comet-title-s">What happened</h4>
            <MarkdownPreview className="prose-sm py-2">
              {report.content}
            </MarkdownPreview>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">No content available.</p>
        )}
      </div>
    </ResizableSidePanel>
  );
}
