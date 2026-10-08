import React, { useState } from "react";
import {
  ArrowUpRight,
  CircleCheck,
  Eye,
  EyeOff,
  Hash,
  ThumbsDown,
  Undo2,
  Users,
} from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import { Button } from "@/ui/button";
import {
  ButtonWithDropdown,
  ButtonWithDropdownContent,
  ButtonWithDropdownItem,
  ButtonWithDropdownTrigger,
} from "@/ui/button-with-dropdown";
import { Card } from "@/ui/card";
import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatDate } from "@/lib/date";
import { cn } from "@/lib/utils";
import IssueSeverityBadge from "@/v2/pages/SignalsPage/IssuesTab/IssueSeverityBadge";
import OccurrenceChart from "@/v2/pages/SignalsPage/IssuesTab/OccurrenceChart";
import AffectedTracesSample from "@/v2/pages/SignalsPage/IssuesTab/AffectedTracesSample";
import CloseAsNotUsefulDialog from "@/v2/pages/SignalsPage/IssuesTab/CloseAsNotUsefulDialog";
import { formatOccurrences } from "@/v2/pages/SignalsPage/helpers";
import useAgentInsightsIssue from "@/api/signals/useAgentInsightsIssue";
import useUpdateAgentInsightsIssueMutation from "@/api/signals/useUpdateAgentInsightsIssueMutation";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

type IssueDetailProps = {
  issue: AgentInsightsIssue;
  projectId: string;
  canConfigure: boolean;
  // Enables "Close as Not useful" (guidance feature toggle).
  canCloseAsNotUseful?: boolean;
};

// Figma: 24px outline buttons with 14px text and icons, 4px radius.
const ACTION_BUTTON_CLASS = "h-6 gap-1 rounded px-2 text-sm font-medium";

// Figma: 227px menu, 24px items with 14px regular text.
const MENU_CONTENT_CLASS = "w-[227px] p-1.5";
const MENU_ITEM_CLASS = "h-6 gap-1.5 p-1";

type StatusChange = {
  status: AGENT_INSIGHTS_ISSUE_STATUS;
  closeNote?: string;
};

const MetaItem: React.FC<{
  icon: React.ElementType;
  label: string;
  value: React.ReactNode;
}> = ({ icon: Icon, label, value }) => (
  <span className="comet-body-s flex items-center gap-1 whitespace-nowrap text-foreground">
    <Icon className="size-3.5 text-light-slate" />
    {label}: <span className="text-foreground">{value}</span>
  </span>
);

const SectionCard: React.FC<{
  title: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
}> = ({ title, children, className, style }) => (
  <Card
    className={cn("flex flex-col gap-2 p-3 shadow-none", className)}
    style={style}
  >
    <div className="comet-body-xs-accented text-muted-slate">{title}</div>
    {children}
  </Card>
);

const ClosedInfoRow: React.FC<{
  label: string;
  children: React.ReactNode;
}> = ({ label, children }) => (
  <div className="flex items-start gap-2.5">
    <span className="comet-body-xs-accented w-16 shrink-0 text-muted-slate">
      {label}
    </span>
    <span className="comet-body-xs min-w-0 flex-1 break-words text-foreground">
      {children}
    </span>
  </div>
);

const ClosedInfo: React.FC<{ issue: AgentInsightsIssue }> = ({ issue }) => {
  const isNotUseful = issue.status === AGENT_INSIGHTS_ISSUE_STATUS.closed;
  const closedBy = [
    issue.status_changed_by,
    issue.status_changed_at &&
      formatDate(issue.status_changed_at, { format: "D MMM YYYY, HH:mm" }),
  ]
    .filter(Boolean)
    .join(", ");

  return (
    <div className="flex flex-col gap-1 rounded-md border bg-soft-background px-3 py-2">
      <ClosedInfoRow label="Closed as">
        {isNotUseful
          ? "Not useful. Diagnostics won’t raise this or similar findings in future runs."
          : "Resolved"}
      </ClosedInfoRow>
      {closedBy && <ClosedInfoRow label="Closed by">{closedBy}</ClosedInfoRow>}
      {isNotUseful && issue.close_note && (
        <ClosedInfoRow label="Reason">{issue.close_note}</ClosedInfoRow>
      )}
    </div>
  );
};

const IssueDetail: React.FC<IssueDetailProps> = ({
  issue,
  projectId,
  canConfigure,
  canCloseAsNotUseful = false,
}) => {
  const { data: detail } = useAgentInsightsIssue({
    issueId: issue.id,
    projectId,
  });

  const updateMutation = useUpdateAgentInsightsIssueMutation();
  const { toast } = useToast();
  const [notUsefulOpen, setNotUsefulOpen] = useState(false);

  const isOpen = issue.status === AGENT_INSIGHTS_ISSUE_STATUS.open;

  // Applies a status change; with `undoToast`, confirms it with a toast whose Undo
  // re-sends the previous status and note.
  const changeStatus = (
    change: StatusChange,
    undoToast?: { title: string; description?: string },
    onSuccess?: () => void,
  ) => {
    const previous: StatusChange = {
      status: issue.status,
      closeNote: issue.close_note,
    };
    updateMutation.mutate(
      { issueId: issue.id, projectId, ...change },
      {
        onSuccess: () => {
          onSuccess?.();
          if (!undoToast) return;
          toast({
            title: undoToast.title,
            description: undoToast.description && (
              <span className="text-muted-slate">{undoToast.description}</span>
            ),
            actions: [
              <ToastAction
                key="undo"
                variant="link"
                size="sm"
                className="comet-body-s h-6 gap-1 px-0 font-normal"
                altText="Undo"
                onClick={() =>
                  updateMutation.mutate({
                    issueId: issue.id,
                    projectId,
                    ...previous,
                  })
                }
              >
                <Undo2 className="size-3.5" />
                Undo
              </ToastAction>,
            ],
          });
        },
      },
    );
  };

  const handleResolve = () => {
    trackEvent(OpikEvent.DIAGNOSTICS_ISSUE_RESOLVED, {
      project_id: projectId,
      issue_id: issue.id,
      severity: issue.severity,
    });
    changeStatus({ status: AGENT_INSIGHTS_ISSUE_STATUS.resolved });
  };

  const handleCloseAsNotUseful = (closeNote: string) =>
    changeStatus(
      { status: AGENT_INSIGHTS_ISSUE_STATUS.closed, closeNote },
      {
        title: "Issue was closed as Not useful",
        description: "This will help future runs avoid similar issues",
      },
      () => setNotUsefulOpen(false),
    );

  const handleReopen = () => {
    trackEvent(OpikEvent.DIAGNOSTICS_ISSUE_REOPENED, {
      project_id: projectId,
      issue_id: issue.id,
      severity: issue.severity,
    });
    changeStatus(
      { status: AGENT_INSIGHTS_ISSUE_STATUS.open },
      issue.status === AGENT_INSIGHTS_ISSUE_STATUS.closed
        ? {
            title: "Issue was reopened",
            description:
              "The issue is open again and will no longer guide future runs",
          }
        : { title: "Issue was reopened" },
    );
  };

  const renderActions = () => {
    if (!isOpen) {
      return (
        <Button
          variant="outline"
          size="2xs"
          className={ACTION_BUTTON_CLASS}
          disabled={updateMutation.isPending}
          onClick={handleReopen}
        >
          <Undo2 className="size-3.5" />
          Reopen
        </Button>
      );
    }

    const closeLabel = (
      <>
        <CircleCheck className="size-3.5" />
        Close issue
      </>
    );

    if (!canCloseAsNotUseful) {
      return (
        <Button
          variant="outline"
          size="2xs"
          className={ACTION_BUTTON_CLASS}
          disabled={updateMutation.isPending}
          onClick={handleResolve}
        >
          {closeLabel}
        </Button>
      );
    }

    return (
      <ButtonWithDropdown>
        <ButtonWithDropdownTrigger
          variant="outline"
          size="2xs"
          className={ACTION_BUTTON_CLASS}
          triggerClassName="-ml-px w-6 rounded px-0 [&>svg]:size-3"
          disabled={updateMutation.isPending}
          onPrimaryClick={handleResolve}
        >
          {closeLabel}
        </ButtonWithDropdownTrigger>
        <ButtonWithDropdownContent align="end" className={MENU_CONTENT_CLASS}>
          <ButtonWithDropdownItem
            className={MENU_ITEM_CLASS}
            onSelect={() => setNotUsefulOpen(true)}
          >
            <ThumbsDown className="size-3" />
            Close as Not useful
          </ButtonWithDropdownItem>
        </ButtonWithDropdownContent>
      </ButtonWithDropdown>
    );
  };

  const handleContinueWithOllie = () => {
    trackEvent(OpikEvent.DIAGNOSTICS_CONTINUE_WITH_OLLIE, {
      project_id: projectId,
      issue_id: issue.id,
    });
    const message = [
      `Help me fix the "${issue.name}" issue detected in this project.`,
      issue.cause ? `Root cause: ${issue.cause}` : null,
      issue.suggested_fix ? `Suggested fix: ${issue.suggested_fix}` : null,
    ]
      .filter(Boolean)
      .join("\n\n");

    window.opikBridge?.startConversation(message);
  };

  const details = detail?.details ?? [];

  // Traces the backend resolved as exhibiting this issue, deduped across the
  // per-day detail rows.
  const exampleTraceIds = Array.from(
    new Set(details.flatMap((d) => d.metadata?.example_trace_ids ?? [])),
  );

  return (
    <div className="flex h-full flex-col">
      <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
        <div className="flex min-w-0 items-center gap-2">
          <IssueSeverityBadge severity={issue.severity} />
          <TooltipWrapper content={issue.name}>
            <span className="comet-body-xs-accented truncate">
              {issue.name}
            </span>
          </TooltipWrapper>
        </div>
        {canConfigure && (
          <div className="flex shrink-0 items-center">{renderActions()}</div>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-3">
        {!isOpen && <ClosedInfo issue={issue} />}
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
          {issue.first_seen && (
            <MetaItem
              icon={Eye}
              label="First seen"
              value={formatDate(issue.first_seen)}
            />
          )}
          {issue.last_seen && (
            <MetaItem
              icon={EyeOff}
              label="Last seen"
              value={formatDate(issue.last_seen)}
            />
          )}
          <MetaItem
            icon={Hash}
            label="Occurrences"
            value={formatOccurrences(
              issue.total_occurrences,
              issue.latest_count,
              issue.days_reported,
            )}
          />
          <MetaItem
            icon={Users}
            label="Users impacted"
            value={issue.users_impacted.toLocaleString()}
          />
        </div>

        {issue.description && (
          <SectionCard title="Summary">
            <p className="comet-body-xs text-foreground">{issue.description}</p>
          </SectionCard>
        )}

        {isOpen && (issue.cause || issue.suggested_fix) && (
          <SectionCard
            style={{ borderColor: "var(--color-ollie)" }}
            title={
              <span className="flex items-center gap-1.5 text-muted-slate">
                <OllieOwl className="size-4 text-[var(--color-ollie)]" />
                Ollie fix
              </span>
            }
          >
            {issue.cause && (
              <p className="comet-body-xs text-foreground">{issue.cause}</p>
            )}
            <Button
              variant="outline"
              size="2xs"
              className="mt-1 self-start"
              onClick={handleContinueWithOllie}
            >
              Continue with Ollie
              <ArrowUpRight className="ml-1.5 size-3" />
            </Button>
          </SectionCard>
        )}

        {details.length > 0 && (
          <SectionCard title="Occurrence over time">
            <OccurrenceChart data={details} />
          </SectionCard>
        )}

        <SectionCard title="Affected traces sample">
          <AffectedTracesSample
            projectId={projectId}
            traceIds={exampleTraceIds}
          />
        </SectionCard>
      </div>
      {canCloseAsNotUseful && (
        <CloseAsNotUsefulDialog
          open={notUsefulOpen}
          setOpen={setNotUsefulOpen}
          onConfirm={handleCloseAsNotUseful}
          isPending={updateMutation.isPending}
        />
      )}
    </div>
  );
};

export default IssueDetail;
