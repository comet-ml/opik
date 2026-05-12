import { type ReactNode, useEffect, useState } from "react";
import { useParams } from "@tanstack/react-router";
import { AlertTriangle, Loader2, Play, Pause, Settings2 } from "lucide-react";
import briefingBulbIcon from "@/icons/briefing-bulb.svg";
import briefingBubbleIcon from "@/icons/briefing-bubble.svg";
import { formatRelativeDateTime, formatUtcTimeAsLocal } from "@/lib/date";
import { Button } from "@/ui/button";
import { Skeleton } from "@/ui/skeleton";
import { Switch } from "@/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import useReports from "@/api/projects/useReports";
import useReportPreference from "@/api/projects/useReportPreference";
import useGenerateReportMutation from "@/api/projects/useGenerateReportMutation";
import useUpdateReportPreferenceMutation from "@/api/projects/useUpdateReportPreferenceMutation";
import { OllieReport, ReportStatus } from "@/types/ollie-reports";
import ReportPanel from "./ReportPanel";

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
        <span className="size-2 rounded-full bg-success" />
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
  scheduleTimeLocal,
  onRunNow,
}: {
  scheduleTimeLocal: string;
  onRunNow: () => void;
}) {
  return (
    <BriefingRow dashed>
      <span className="text-light-slate">
        Scheduled: Tomorrow, {scheduleTimeLocal}
      </span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0 text-muted-slate"
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
  return (
    <BriefingRow onClick={() => onSelect(report)}>
      <span>{formatRelativeDateTime(report.created_at)}</span>
    </BriefingRow>
  );
}

function TurnOnDialog({
  open,
  onOpenChange,
  onConfirm,
  scheduleTimeLocal,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (runImmediately: boolean) => void;
  scheduleTimeLocal: string;
}) {
  const [runImmediately, setRunImmediately] = useState(true);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Turn on daily briefing?</DialogTitle>
          <DialogDescription asChild className="pt-6">
            <div className="flex flex-col gap-6">
              <p>
                Ollie will run every day at {scheduleTimeLocal}, reviewing your
                traces and telling you exactly what to improve.
              </p>
              <p>
                Runs on your Ollie tokens. You can turn it off at any time.{" "}
                <button className="underline underline-offset-4 hover:text-primary">
                  Learn more
                </button>
              </p>
              <div className="flex items-center gap-3">
                <Switch
                  checked={runImmediately}
                  onCheckedChange={setRunImmediately}
                  size="sm"
                />
                <div>
                  <p className="font-medium text-foreground">
                    Run the first report immediately on activation
                  </p>
                  <p className="text-light-slate">
                    The next scheduled run will be tomorrow at{" "}
                    {scheduleTimeLocal}.
                  </p>
                </div>
              </div>
            </div>
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={() => onConfirm(runImmediately)}>Turn on</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function SettingsDialog({
  open,
  onOpenChange,
  enabled,
  onSave,
  scheduleTimeLocal,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  enabled: boolean;
  onSave: (enabled: boolean) => void;
  scheduleTimeLocal: string;
}) {
  const [localEnabled, setLocalEnabled] = useState(enabled);

  // Sync local state when the prop changes (e.g., after mutation settles)
  useEffect(() => {
    setLocalEnabled(enabled);
  }, [enabled]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[580px]">
        <DialogHeader>
          <DialogTitle>Daily briefing settings</DialogTitle>
          <DialogDescription asChild className="pt-6">
            <div className="flex flex-col gap-6">
              <p className="text-foreground">
                <span className="font-medium">Next run:</span> Next report
                scheduled for tomorrow at {scheduleTimeLocal}.
              </p>
              <p className="text-foreground">
                <span className="font-medium">Billing:</span> Runs on your Ollie
                tokens. {/* TODO: add billing link target */}
                <button className="underline underline-offset-4 hover:text-primary">
                  View billing
                </button>
              </p>
              <div className="flex items-center gap-3">
                <Switch
                  checked={localEnabled}
                  onCheckedChange={setLocalEnabled}
                  size="sm"
                />
                <div>
                  {localEnabled ? (
                    <>
                      <p className="font-medium text-foreground">
                        Daily briefing active
                      </p>
                      <p className="text-light-slate">
                        Turn off to pause automated reports. You can reactivate
                        at any time.
                      </p>
                    </>
                  ) : (
                    <>
                      <p className="font-medium text-foreground">
                        Daily briefing is paused
                      </p>
                      <p className="text-light-slate">
                        Turn on to resume automated reports.
                      </p>
                    </>
                  )}
                </div>
              </div>
            </div>
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={() => onSave(localEnabled)}>Save changes</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const POLLING_INTERVAL = 10_000;

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
  const scheduleTimeLocal = preference?.schedule_time_utc
    ? formatUtcTimeAsLocal(preference.schedule_time_utc)
    : "";

  const { data: reportsData, isPending: isReportsPending } = useReports(
    { projectId },
    {
      enabled: isEnabled,
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
    : completedReports.slice(0, 3);

  const handleRunNow = () => {
    generateMutation.mutate({ projectId });
  };

  const handleTurnOn = (runImmediately: boolean) => {
    setShowTurnOnDialog(false);
    updatePreferenceMutation.mutate(
      { projectId, enabled: true },
      {
        onSuccess: () => {
          if (runImmediately) {
            generateMutation.mutate({ projectId });
          }
        },
      },
    );
  };

  const handleSaveSettings = (enabled: boolean) => {
    setShowSettingsDialog(false);
    updatePreferenceMutation.mutate({ projectId, enabled });
  };

  const handleReactivate = () => {
    updatePreferenceMutation.mutate({ projectId, enabled: true });
  };

  const handleSelectReport = (report: OllieReport) => {
    setSelectedReport(report);
  };

  const isLoading = isPreferencePending || (isEnabled && isReportsPending);

  return (
    <section>
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-s-accented">Daily briefing</h2>
          {!isLoading &&
            (isPaused ? <PausedBadge /> : <StatusBadge enabled={isEnabled} />)}
        </div>
        {isEnabled && (
          <button
            className="text-muted-foreground hover:text-foreground"
            onClick={() => setShowSettingsDialog(true)}
          >
            <Settings2 className="size-4" />
          </button>
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
          description={`Next briefing is scheduled for tomorrow at ${scheduleTimeLocal}.`}
          actionLabel="Run now"
          onAction={handleRunNow}
        />
      )}

      {(isEnabled || isPaused) && reports.length > 0 && (
        <div className="flex flex-col gap-2">
          {isPaused && <PausedRow onReactivate={handleReactivate} />}

          {isEnabled && hasRunning && <RunningRow />}

          {isEnabled && hasFailed && <FailedRow onRetry={handleRunNow} />}

          {isEnabled && !hasRunning && !hasFailed && (
            <ScheduledRow
              scheduleTimeLocal={scheduleTimeLocal}
              onRunNow={handleRunNow}
            />
          )}

          {displayReports.map((report) => (
            <ReportRow
              key={report.id}
              report={report}
              onSelect={handleSelectReport}
            />
          ))}

          {!showMore && completedReports.length > 3 && (
            <button
              className="text-sm font-medium underline"
              onClick={() => setShowMore(true)}
            >
              Show more
            </button>
          )}
        </div>
      )}

      <TurnOnDialog
        open={showTurnOnDialog}
        onOpenChange={setShowTurnOnDialog}
        onConfirm={handleTurnOn}
        scheduleTimeLocal={scheduleTimeLocal}
      />

      {preference != null && (
        <SettingsDialog
          open={showSettingsDialog}
          onOpenChange={setShowSettingsDialog}
          enabled={preference.enabled}
          onSave={handleSaveSettings}
          scheduleTimeLocal={scheduleTimeLocal}
        />
      )}

      <ReportPanel
        report={selectedReport}
        onClose={() => setSelectedReport(null)}
      />
    </section>
  );
}
