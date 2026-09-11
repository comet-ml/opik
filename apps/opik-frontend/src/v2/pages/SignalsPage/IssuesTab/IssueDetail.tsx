import React from "react";
import {
  ArrowUpRight,
  CircleCheck,
  Eye,
  EyeOff,
  Hash,
  Undo2,
  Users,
} from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import { Button } from "@/ui/button";
import { Card } from "@/ui/card";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Separator } from "@/ui/separator";
import { formatDate } from "@/lib/date";
import { cn } from "@/lib/utils";
import IssueSeverityBadge from "@/v2/pages/SignalsPage/IssuesTab/IssueSeverityBadge";
import OccurrenceChart from "@/v2/pages/SignalsPage/IssuesTab/OccurrenceChart";
import AffectedTracesSample from "@/v2/pages/SignalsPage/IssuesTab/AffectedTracesSample";
import { formatOccurrences } from "@/v2/pages/SignalsPage/helpers";
import useAgentInsightsIssue from "@/api/signals/useAgentInsightsIssue";
import useUpdateAgentInsightsIssueMutation from "@/api/signals/useUpdateAgentInsightsIssueMutation";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

type IssueDetailProps = {
  issue: AgentInsightsIssue;
  projectId: string;
  canConfigure: boolean;
};

const STATUS_ACTIONS: {
  status: AGENT_INSIGHTS_ISSUE_STATUS;
  label: string;
  icon: React.ElementType;
}[] = [
  {
    status: AGENT_INSIGHTS_ISSUE_STATUS.resolved,
    label: "Resolve",
    icon: CircleCheck,
  },
  {
    status: AGENT_INSIGHTS_ISSUE_STATUS.open,
    label: "Reopen",
    icon: Undo2,
  },
];

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

const IssueDetail: React.FC<IssueDetailProps> = ({
  issue,
  projectId,
  canConfigure,
}) => {
  const { data: detail } = useAgentInsightsIssue({
    issueId: issue.id,
    projectId,
  });

  const updateMutation = useUpdateAgentInsightsIssueMutation();

  const setStatus = (status: AGENT_INSIGHTS_ISSUE_STATUS) => {
    trackEvent(
      status === AGENT_INSIGHTS_ISSUE_STATUS.resolved
        ? OpikEvent.DIAGNOSTICS_ISSUE_RESOLVED
        : OpikEvent.DIAGNOSTICS_ISSUE_REOPENED,
      { project_id: projectId, issue_id: issue.id, severity: issue.severity },
    );
    updateMutation.mutate({ issueId: issue.id, projectId, status });
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
          <div className="flex shrink-0 items-center gap-1">
            {STATUS_ACTIONS.filter(
              (action) => action.status !== issue.status,
            ).map(({ status, label, icon: Icon }, index) => (
              <React.Fragment key={status}>
                {index > 0 && (
                  <Separator orientation="vertical" className="h-4" />
                )}
                <Button
                  variant="ghost"
                  size="2xs"
                  disabled={updateMutation.isPending}
                  onClick={() => setStatus(status)}
                >
                  <Icon className="mr-1 size-3" />
                  {label}
                </Button>
              </React.Fragment>
            ))}
          </div>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-3">
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

        {(issue.cause || issue.suggested_fix) && (
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
    </div>
  );
};

export default IssueDetail;
