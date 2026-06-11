import React from "react";
import {
  ArrowUpRight,
  CircleCheck,
  Coins,
  Crosshair,
  EyeOff,
  Eye,
  Hash,
  MoreVertical,
  Timer,
  Users,
} from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { Issue } from "@/types/signals";
import { Button } from "@/ui/button";
import { Card } from "@/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { cn } from "@/lib/utils";
import {
  SEVERITY_DOT_COLOR_MAP,
  formatRate,
} from "@/v2/pages/SignalsPage/helpers";
import OccurrenceChart from "@/v2/pages/SignalsPage/IssuesTab/OccurrenceChart";
import SuggestedPromptDiff from "@/v2/pages/SignalsPage/IssuesTab/SuggestedPromptDiff";

type IssueDetailProps = {
  issue: Issue;
  onResolve: (issue: Issue) => void;
  onArchive: (issue: Issue) => void;
};

const MetaItem: React.FC<{
  icon: React.ElementType;
  label: string;
  value: React.ReactNode;
}> = ({ icon: Icon, label, value }) => (
  <span className="comet-body-s flex items-center gap-1 whitespace-nowrap text-foreground">
    <Icon className="size-3.5" />
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

const IssueDetail: React.FC<IssueDetailProps> = ({
  issue,
  onResolve,
  onArchive,
}) => {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        {/* Header */}
        <div className="flex items-start justify-between gap-2">
          <h2 className="comet-body-accented flex items-center gap-2">
            <span
              className="size-2 rounded-full"
              style={{
                backgroundColor: SEVERITY_DOT_COLOR_MAP[issue.severity],
              }}
            />
            {issue.name}
          </h2>
          <div className="flex shrink-0 items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => onResolve(issue)}
            >
              <CircleCheck className="mr-1.5 size-3.5" />
              Resolve
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="icon-sm">
                  <MoreVertical className="size-3.5" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => onArchive(issue)}>
                  <EyeOff className="mr-2 size-3.5" />
                  Archive
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>

        {/* Meta row */}
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
          <MetaItem
            icon={Eye}
            label="First seen"
            value={formatDate(issue.first_seen_at, { format: "D MMM" })}
          />
          <MetaItem
            icon={EyeOff}
            label="Last seen"
            value={formatDate(issue.last_seen_at, { format: "D MMM" })}
          />
          <MetaItem
            icon={Hash}
            label="Occurrences"
            value={issue.occurrences.toLocaleString()}
          />
          <MetaItem
            icon={Users}
            label="User impacted"
            value={issue.users_impacted.toLocaleString()}
          />
          <MetaItem
            icon={Crosshair}
            label="Rate"
            value={formatRate(issue.rate)}
          />
        </div>
      </div>

      {/* Summary */}
      <SectionCard title="Summary">
        <p className="comet-body-xs text-foreground">{issue.summary}</p>
      </SectionCard>

      {/* Ollie fix */}
      {issue.ollie_fix && (
        <SectionCard
          style={{ borderColor: "var(--color-ollie)" }}
          title={
            <span className="flex items-center gap-1.5 text-muted-slate">
              <OllieOwl className="size-4 text-[var(--color-ollie)]" />
              Ollie fix
            </span>
          }
        >
          <p className="comet-body-xs text-foreground">
            Analysed {issue.ollie_fix.analyzed_traces.toLocaleString()} traces
            and found the root cause: {issue.ollie_fix.root_cause}
          </p>
          {issue.ollie_fix.suggested_prompt_change && (
            <Accordion type="single" collapsible>
              <AccordionItem value="prompt" className="border-none">
                <AccordionTrigger className="comet-body-xs py-1 text-foreground hover:no-underline">
                  Suggested prompt change
                </AccordionTrigger>
                <AccordionContent className="pb-0">
                  <SuggestedPromptDiff
                    lines={issue.ollie_fix.suggested_prompt_change}
                  />
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          )}
        </SectionCard>
      )}

      {/* Occurrence over time */}
      <SectionCard title="Occurrence over time">
        <OccurrenceChart data={issue.occurrences_over_time} />
      </SectionCard>

      {/* Example traces */}
      <SectionCard title="Example traces">
        <div className="flex flex-col">
          {issue.example_traces.map((trace, index) => (
            <div
              key={`${trace.id}-${index}`}
              className="comet-body-s flex items-center gap-4 border-b border-border py-2 last:border-none"
            >
              <span className="flex w-24 items-center gap-1 truncate font-mono text-muted-slate">
                {trace.id.slice(0, 4)}...{trace.id.slice(-3)}
              </span>
              <span className="flex items-center gap-1 text-muted-slate">
                <Timer className="size-3.5" />
                {formatDuration(trace.duration * 1000)}
              </span>
              <span className="flex items-center gap-1 text-muted-slate">
                <Hash className="size-3.5" />
                {trace.span_count}
              </span>
              <span className="flex items-center gap-1 text-muted-slate">
                <Coins className="size-3.5" />
                {formatCost(trace.cost)}
              </span>
              <span className="truncate text-muted-slate">{trace.model}</span>
              <span className="ml-auto whitespace-nowrap text-light-slate">
                {formatDate(trace.last_updated_at)}
              </span>
            </div>
          ))}
        </div>
        <Button variant="link" size="sm" className="mt-1 self-start px-0">
          View all
          <ArrowUpRight className="ml-1 size-3.5" />
        </Button>
      </SectionCard>
    </div>
  );
};

export default IssueDetail;
