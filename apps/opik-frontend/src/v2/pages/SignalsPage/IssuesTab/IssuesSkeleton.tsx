import React from "react";
import { Skeleton } from "@/ui/skeleton";

// One issue-list row placeholder: title + severity pill, description line, and
// the occurrences / last-seen meta line (mirrors IssueListItem).
const IssueRowSkeleton: React.FC = () => (
  <div className="flex flex-col gap-1.5 border-b border-border p-3">
    <div className="flex items-center justify-between gap-2">
      <Skeleton className="h-3.5 w-40" />
      <Skeleton className="h-4 w-14 rounded-full" />
    </div>
    <Skeleton className="h-3 w-full" />
    <div className="flex gap-4">
      <Skeleton className="h-3 w-20" />
      <Skeleton className="h-3 w-24" />
    </div>
  </div>
);

// Loading placeholder for the issues two-pane. Mirrors the live layout: the
// list collapses to a single trigger below lg, the report fills the rest.
const IssuesSkeleton: React.FC = () => (
  <div className="flex min-h-0 flex-1 flex-col gap-2 lg:flex-row">
    {/* Compact: collapsed list trigger */}
    <Skeleton className="h-9 w-full shrink-0 lg:hidden" />

    {/* Wide: list column */}
    <div className="hidden h-full w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background lg:flex">
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-border bg-soft-background px-3">
        <Skeleton className="h-3.5 w-16" />
        <Skeleton className="h-5 w-20" />
      </div>
      <div className="flex flex-col">
        {[0, 1, 2, 3].map((i) => (
          <IssueRowSkeleton key={i} />
        ))}
      </div>
    </div>

    {/* Report column */}
    <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-md border bg-background">
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-border bg-soft-background px-3">
        <Skeleton className="h-3.5 w-44" />
        <Skeleton className="h-5 w-20" />
      </div>
      <div className="flex flex-1 flex-col gap-2 p-3">
        {/* Meta row */}
        <div className="flex flex-wrap gap-x-5 gap-y-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-3.5 w-24" />
          ))}
        </div>
        {/* Report cards */}
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    </div>
  </div>
);

export default IssuesSkeleton;
