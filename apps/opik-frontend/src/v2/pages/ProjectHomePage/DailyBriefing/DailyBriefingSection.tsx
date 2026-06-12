import { type ReactNode, useState } from "react";
import { useParams } from "@tanstack/react-router";
import { AlertTriangle, Loader2, Play, Pause, Settings2 } from "lucide-react";
import briefingBulbIcon from "@/icons/briefing-bulb.svg";
import briefingBubbleIcon from "@/icons/briefing-bubble.svg";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  formatRelativeDateTime,
  formatUtcTimeAsLocal,
  formatLocalTimeAsUtc,
  parseUtcTimeToLocalDate,
} from "@/lib/date";
import { Button } from "@/ui/button";
import { Skeleton } from "@/ui/skeleton";
import useReports from "@/api/projects/useReports";
import useReportPreference from "@/api/projects/useReportPreference";
import useGenerateReportMutation from "@/api/projects/useGenerateReportMutation";
import useUpdateReportPreferenceMutation from "@/api/projects/useUpdateReportPreferenceMutation";
import {
  OllieReport,
  RecommendedAction,
  ReportPreferenceSettings,
  ReportStatus,
} from "@/types/ollie-reports";
import ReportPanel from "./ReportPanel";
import TurnOnDialog from "./TurnOnDialog";
import SettingsDialog from "./SettingsDialog";

function getNextRunLabel(scheduleTimeUtc: string) {
  const day =
    parseUtcTimeToLocalDate(scheduleTimeUtc) > new Date()
      ? "today"
      : "tomorrow";
  return `${day} at ${formatUtcTimeAsLocal(scheduleTimeUtc)}`;
}

function LoadingSkeleton() {
  return (
    <div className="flex flex-col gap-2">
      {Array.from({ length: 3 }).map((_, i) => (
        <Skeleton key={i} className="h-12 w-full rounded-md" />
      ))}
    </div>
  );
}

function StatusBadge({ enabled }: { enabled: boolean }) {
  if (enabled) {
    return (
      <span className="flex items-center gap-1.5 text-xs">
        <span className="size-1.5 rounded-full bg-emerald-400" />
        Active
      </span>
    );
  }

  return (
    <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="size-1.5 rounded-full bg-chart-red" />
      Inactive
    </span>
  );
}

function PausedBadge() {
  return (
    <span className="flex items-center gap-1.5 text-xs">
      <Pause className="size-3 text-chart-red" />
      Paused
    </span>
  );
}

function EmptyState({
  icon,
  title,
  description,
  actionLabel,
  onAction,
}: {
  icon: string;
  title: string;
  description: string;
  actionLabel: string;
  onAction: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 rounded-lg border bg-background p-10">
      <img src={icon} alt="" className="mb-4 w-7" />
      <p className="comet-body-s font-medium">{title}</p>
      <p className="comet-body-xs mt-2 text-center text-muted-foreground">
        {description}
      </p>
      <button
        className="comet-body-xs mt-3 underline underline-offset-4 hover:text-primary"
        onClick={onAction}
      >
        {actionLabel}
      </button>
    </div>
  );
}

function BriefingRow({
  children,
  dashed,
  onClick,
}: {
  children: ReactNode;
  dashed?: boolean;
  onClick?: () => void;
}) {
  const Tag = onClick ? "button" : "div";
  return (
    <Tag
      className={`comet-body-xs flex w-full items-center justify-between rounded-md border px-3 py-2 ${
        dashed ? "border-dashed" : "bg-background"
      } ${onClick ? "text-left hover:border-primary hover:bg-primary/5" : ""}`}
      onClick={onClick}
    >
      {children}
    </Tag>
  );
}

function RunningRow() {
  return (
    <BriefingRow>
      <span>
        <span className="font-medium">Running report...</span>{" "}
        <span className="text-muted-foreground">
          It might take a few minutes
        </span>
      </span>
      <Loader2 className="size-4 animate-spin text-primary" />
    </BriefingRow>
  );
}

function ScheduledRow({
  nextRunLabel,
  onRunNow,
}: {
  nextRunLabel: string;
  onRunNow: () => void;
}) {
  return (
    <BriefingRow dashed>
      <span className="text-light-slate">Scheduled: {nextRunLabel}</span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0 text-foreground"
        onClick={onRunNow}
      >
        <Play className="size-3" />
        Run now
      </Button>
    </BriefingRow>
  );
}

function FailedRow({ onRetry }: { onRetry: () => void }) {
  return (
    <BriefingRow>
      <span className="flex items-center gap-2 text-destructive">
        <AlertTriangle className="size-3.5" />
        Briefing failed
      </span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0"
        onClick={onRetry}
      >
        <Play className="size-3" />
        Run again
      </Button>
    </BriefingRow>
  );
}

function PausedRow({ onReactivate }: { onReactivate: () => void }) {
  return (
    <BriefingRow dashed>
      <span className="text-light-slate">Reactivate daily briefing</span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0 text-muted-slate"
        onClick={onReactivate}
      >
        <Play className="size-3" />
        Reactivate
      </Button>
    </BriefingRow>
  );
}

function ReportRow({
  report,
  onSelect,
}: {
  report: OllieReport;
  onSelect: (report: OllieReport) => void;
}) {
  const actionCount = report.recommended_actions?.length ?? 0;
  return (
    <BriefingRow onClick={() => onSelect(report)}>
      <span className="flex items-center gap-2">
        <span className="text-foreground">
          {formatRelativeDateTime(report.created_at)}
        </span>
        {actionCount > 0 && (
          <span className="text-muted-slate">
            {actionCount} {actionCount === 1 ? "action" : "actions"}
          </span>
        )}
      </span>
    </BriefingRow>
  );
}

const POLLING_INTERVAL = 10_000;
const REPORT_PREVIEW_COUNT = 3;
const DEFAULT_SCHEDULE_TIME = formatLocalTimeAsUtc("07:00:00");

export default function DailyBriefingSection() {
  const { projectId } = useParams({ strict: false }) as {
    projectId: string;
  };

  const [showTurnOnDialog, setShowTurnOnDialog] = useState(false);
  const [showSettingsDialog, setShowSettingsDialog] = useState(false);
  const [showMore, setShowMore] = useState(false);
  const [selectedReport, setSelectedReport] = useState<OllieReport | null>(
    null,
  );
  const { data: preference, isPending: isPreferencePending } =
    useReportPreference({ projectId });
  const isEnabled = preference?.enabled ?? false;
  const scheduleTimeUtc = preference?.schedule_time ?? DEFAULT_SCHEDULE_TIME;
  const nextRunLabel = getNextRunLabel(scheduleTimeUtc);

  const { data: reportsData, isPending: isReportsPending } = useReports(
    { projectId },
    {
      enabled: !isPreferencePending,
      refetchInterval: (query) => {
        const hasRunning = query.state.data?.content?.some(
          (r) => r.status === ReportStatus.PENDING,
        );
        return hasRunning ? POLLING_INTERVAL : false;
      },
    },
  );

  const generateMutation = useGenerateReportMutation();
  const updatePreferenceMutation = useUpdateReportPreferenceMutation();

  const reports = reportsData?.content ?? [];
  const latestReport = reports[0];
  const hasRunning = latestReport?.status === ReportStatus.PENDING;
  const hasFailed = latestReport?.status === ReportStatus.FAILED;
  const isPaused =
    preference != null && !preference.enabled && reports.length > 0;
  const completedReports = reports.filter(
    (r) => r.status === ReportStatus.COMPLETED,
  );
  const displayReports = showMore
    ? completedReports
    : completedReports.slice(0, REPORT_PREVIEW_COUNT);

  const handleRunNow = () => {
    generateMutation.mutate({ projectId });
  };

  const handleTurnOn = (runImmediately: boolean) => {
    setShowTurnOnDialog(false);
    updatePreferenceMutation.mutate(
      {
        projectId,
        enabled: true,
        schedule_time: DEFAULT_SCHEDULE_TIME,
      },
      {
        onSuccess: () => {
          if (runImmediately) {
            generateMutation.mutate({ projectId });
          }
        },
      },
    );
  };

  const handleSaveSettings = (settings: ReportPreferenceSettings) => {
    setShowSettingsDialog(false);
    updatePreferenceMutation.mutate({
      projectId,
      enabled: settings.enabled,
      schedule_time: settings.scheduleTime,
      custom_prompt: settings.customPrompt,
    });
  };

  const handleReactivate = () => {
    updatePreferenceMutation.mutate({
      projectId,
      enabled: true,
      schedule_time: preference?.schedule_time ?? DEFAULT_SCHEDULE_TIME,
    });
  };

  const handleSelectReport = (report: OllieReport) => {
    setSelectedReport(report);
  };

  const handleStartConversation = (action: RecommendedAction) => {
    setSelectedReport(null);
    window.opikBridge?.startConversation(action.prompt);
  };

  const isLoading = isPreferencePending || isReportsPending;

  return (
    <section>
      <div className="mb-1.5 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-s-accented">Daily briefing</h2>
          {!isLoading &&
            (isPaused ? <PausedBadge /> : <StatusBadge enabled={isEnabled} />)}
        </div>
        {isEnabled && (
          <TooltipWrapper content="Briefing settings">
            <button
              className="text-foreground hover:text-foreground"
              onClick={() => setShowSettingsDialog(true)}
            >
              <Settings2 className="size-4" />
            </button>
          </TooltipWrapper>
        )}
      </div>

      {isLoading && <LoadingSkeleton />}

      {!isLoading && !isEnabled && !isPaused && (
        <EmptyState
          icon={briefingBulbIcon}
          title="Daily recommendations to improve your agent"
          description="Every day Ollie reviews the last 24 hours of your project — summarizing what changed, flagging anomalies, and suggesting what to do next."
          actionLabel="Turn on daily briefing"
          onAction={() => setShowTurnOnDialog(true)}
        />
      )}

      {!isLoading && isEnabled && reports.length === 0 && (
        <EmptyState
          icon={briefingBubbleIcon}
          title="No briefings yet"
          description={`Next briefing is scheduled for ${nextRunLabel}.`}
          actionLabel="Run now"
          onAction={handleRunNow}
        />
      )}

      {(isEnabled || isPaused) && reports.length > 0 && (
        <div className="flex flex-col gap-1.5">
          {isPaused && <PausedRow onReactivate={handleReactivate} />}

          {isEnabled && hasRunning && <RunningRow />}

          {isEnabled && hasFailed && <FailedRow onRetry={handleRunNow} />}

          {isEnabled && !hasRunning && !hasFailed && (
            <ScheduledRow nextRunLabel={nextRunLabel} onRunNow={handleRunNow} />
          )}

          {displayReports.map((report) => (
            <ReportRow
              key={report.id}
              report={report}
              onSelect={handleSelectReport}
            />
          ))}

          {!showMore && completedReports.length > REPORT_PREVIEW_COUNT && (
            <Button
              variant="ghost"
              size="2xs"
              className="mt-1 h-auto self-start p-0 text-muted-slate"
              onClick={() => setShowMore(true)}
            >
              Show more
            </Button>
          )}
        </div>
      )}

      <TurnOnDialog
        open={showTurnOnDialog}
        onOpenChange={setShowTurnOnDialog}
        onConfirm={handleTurnOn}
        scheduleTimeLocal={formatUtcTimeAsLocal(DEFAULT_SCHEDULE_TIME)}
      />

      {preference != null && (
        <SettingsDialog
          open={showSettingsDialog}
          onOpenChange={setShowSettingsDialog}
          enabled={preference.enabled}
          scheduleTime={preference.schedule_time}
          customPrompt={preference.custom_prompt ?? ""}
          onSave={handleSaveSettings}
        />
      )}

      <ReportPanel
        report={selectedReport}
        onClose={() => setSelectedReport(null)}
        onStartConversation={handleStartConversation}
      />
    </section>
  );
}
