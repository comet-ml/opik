import React, { useEffect, useMemo, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { Issue, ISSUE_STATUS, SIGNALS_SORT } from "@/types/signals";
import useProjectIssuesList from "@/api/signals/useProjectIssuesList";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import IssueListItem from "@/v2/pages/SignalsPage/IssuesTab/IssueListItem";
import IssueDetail from "@/v2/pages/SignalsPage/IssuesTab/IssueDetail";

const SORT_OPTIONS: { value: SIGNALS_SORT; label: string }[] = [
  { value: SIGNALS_SORT.severity, label: "By severity" },
  { value: SIGNALS_SORT.occurrences, label: "By occurrences" },
  { value: SIGNALS_SORT.last_seen, label: "By last seen" },
  { value: SIGNALS_SORT.first_seen, label: "By first seen" },
];

type IssuesTabProps = {
  projectId: string;
};

const IssuesTab: React.FC<IssuesTabProps> = ({ projectId }) => {
  const [sort, setSort] = useState<SIGNALS_SORT>(SIGNALS_SORT.severity);
  const [activeIssueId, setActiveIssueId] = useQueryParam(
    "issue",
    StringParam,
    { updateType: "replaceIn" },
  );

  const { data, isPending } = useProjectIssuesList({
    projectId,
    status: ISSUE_STATUS.open,
    sort,
  });

  const issues = useMemo(() => data?.content ?? [], [data]);

  // Default selection: first issue once data is available.
  useEffect(() => {
    if (
      issues.length > 0 &&
      !issues.some((issue) => issue.id === activeIssueId)
    ) {
      setActiveIssueId(issues[0].id);
    }
  }, [issues, activeIssueId, setActiveIssueId]);

  const activeIssue = useMemo(
    () => issues.find((issue) => issue.id === activeIssueId) ?? issues[0],
    [issues, activeIssueId],
  );

  const handleSelect = (issue: Issue) => setActiveIssueId(issue.id);

  // TODO(signals-backend): wire to resolve/archive mutations once available.
  const handleResolve = () => {};
  const handleArchive = () => {};

  if (isPending) {
    return <Loader />;
  }

  if (!issues.length) {
    return (
      <NoData
        title="No issues yet"
        message="Once we scan your traces, detected issues will show up here."
        className="h-[400px]"
      />
    );
  }

  return (
    <div className="flex min-h-[500px] gap-4">
      {/* Issue list */}
      <div className="flex w-[360px] shrink-0 flex-col rounded-md border bg-background">
        <div className="flex h-10 items-center justify-between border-b border-border pl-4 pr-2">
          <span className="comet-body-xs-accented">Issues</span>
          <Select
            value={sort}
            onValueChange={(value) => setSort(value as SIGNALS_SORT)}
          >
            <SelectTrigger className="h-7 w-auto gap-1 border-none px-2 text-xs shadow-none hover:shadow-none [&>svg]:size-3 [&>svg]:opacity-100">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SORT_OPTIONS.map((option) => (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  className="text-xs"
                >
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col overflow-y-auto">
          {issues.map((issue) => (
            <IssueListItem
              key={issue.id}
              issue={issue}
              isActive={issue.id === activeIssue?.id}
              onClick={handleSelect}
            />
          ))}
        </div>
      </div>

      {/* Issue detail */}
      <div className="min-w-0 flex-1 rounded-md border bg-background p-4">
        {activeIssue && (
          <IssueDetail
            issue={activeIssue}
            onResolve={handleResolve}
            onArchive={handleArchive}
          />
        )}
      </div>
    </div>
  );
};

export default IssuesTab;
