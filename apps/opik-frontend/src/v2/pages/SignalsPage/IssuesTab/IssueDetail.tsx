import React from "react";
import {
  ArrowUpRight,
  Ban,
  BugPlay,
  CircleCheck,
  Eye,
  EyeOff,
  Hash,
  Users,
} from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import { Button } from "@/ui/button";
import { Card } from "@/ui/card";
import { Tag } from "@/ui/tag";
import { Separator } from "@/ui/separator";
import { formatDate } from "@/lib/date";
import { cn } from "@/lib/utils";
import {
  STATUS_LABEL_MAP,
  STATUS_TAG_VARIANT_MAP,
} from "@/v2/pages/SignalsPage/helpers";
import OccurrenceChart from "@/v2/pages/SignalsPage/IssuesTab/OccurrenceChart";
import AffectedTracesSample from "@/v2/pages/SignalsPage/IssuesTab/AffectedTracesSample";
import useAgentInsightsIssue from "@/api/signals/useAgentInsightsIssue";
import useUpdateAgentInsightsIssueMutation from "@/api/signals/useUpdateAgentInsightsIssueMutation";

type IssueDetailProps = {
  issue: AgentInsightsIssue;
  projectId: string;
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
  <Card className={cn("flex flex-col gap-2 p-3", className)} style={style}>
    <div className="comet-body-xs-accented text-muted-slate">{title}</div>
    {children}
  </Card>
);

const IssueDetail: React.FC<IssueDetailProps> = ({ issue, projectId }) => {
  const { data: detail } = useAgentInsightsIssue({
    issueId: issue.id,
    projectId,
  });

  const updateMutation = useUpdateAgentInsightsIssueMutation();

  const setStatus = (status: AGENT_INSIGHTS_ISSUE_STATUS) =>
    updateMutation.mutate({ issueId: issue.id, projectId, status });

  // Hand the diagnosis off to the Ollie assistant (the global sidebar bridge is
  // mounted on every non-Ollie page) so the user can continue applying the fix.
  const handleContinueWithOllie = () => {
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

  return (
    <div className="flex flex-col">
      {/* Header */}
      <div className="flex h-10 items-center justify-between gap-2 border-b border-border bg-[#F8FAFC] px-3">
        <div className="flex min-w-0 items-center gap-2">
          <span
            className="flex size-4 shrink-0 items-center justify-center rounded-md"
            style={{ backgroundColor: "hsl(var(--destructive))" }}
          >
            <BugPlay className="size-2 text-white" />
          </span>
          <span className="comet-body-xs-accented truncate">{issue.name}</span>
          <Tag
            size="sm"
            variant={STATUS_TAG_VARIANT_MAP[issue.status]}
            className="shrink-0"
          >
            {STATUS_LABEL_MAP[issue.status]}
          </Tag>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button
            variant="ghost"
            size="2xs"
            disabled={
              updateMutation.isPending ||
              issue.status === AGENT_INSIGHTS_ISSUE_STATUS.resolved
            }
            onClick={() => setStatus(AGENT_INSIGHTS_ISSUE_STATUS.resolved)}
          >
            <CircleCheck className="mr-1.5 size-3" />
            Resolve
          </Button>
          <Separator orientation="vertical" className="h-4" />
          <Button
            variant="ghost"
            size="2xs"
            disabled={
              updateMutation.isPending ||
              issue.status === AGENT_INSIGHTS_ISSUE_STATUS.closed
            }
            onClick={() => setStatus(AGENT_INSIGHTS_ISSUE_STATUS.closed)}
          >
            <Ban className="mr-1.5 size-3" />
            Close
          </Button>
        </div>
      </div>

      {/* Body */}
      <div className="flex flex-col gap-4 p-3">
        {/* Meta row */}
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
            value={issue.total_occurrences.toLocaleString()}
          />
          <MetaItem
            icon={Users}
            label="Users impacted"
            value={issue.users_impacted.toLocaleString()}
          />
        </div>

        {/* Summary */}
        {issue.description && (
          <SectionCard title="Summary">
            <p className="comet-body-xs text-foreground">{issue.description}</p>
          </SectionCard>
        )}

        {/* Ollie fix */}
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

        {/* Occurrence over time */}
        {details.length > 0 && (
          <SectionCard title="Occurrence over time">
            <OccurrenceChart data={details} />
          </SectionCard>
        )}

        {/* Affected traces sample */}
        <SectionCard title="Affected traces sample">
          <AffectedTracesSample projectId={projectId} />
        </SectionCard>
      </div>
    </div>
  );
};

export default IssueDetail;
