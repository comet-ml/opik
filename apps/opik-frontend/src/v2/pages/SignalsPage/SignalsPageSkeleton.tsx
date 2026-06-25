import React from "react";
import { Skeleton } from "@/ui/skeleton";
import IssuesSkeleton from "@/v2/pages/SignalsPage/IssuesTab/IssuesSkeleton";

// Full-page loading placeholder for Diagnostics: stat cards, the actions row,
// and the issues two-pane — matching the live layout so there's no jump on load.
const SignalsPageSkeleton: React.FC = () => (
  <div className="flex min-h-0 flex-1 flex-col gap-4 px-6 pb-3">
    {/* Stat cards (skipped below lg, like the live page) */}
    <div className="hidden gap-4 lg:grid lg:grid-cols-3">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-[78px] w-full" />
      ))}
    </div>

    {/* Last scan + actions row */}
    <div className="flex items-center gap-2">
      <Skeleton className="h-4 w-40" />
      <div className="ml-auto flex items-center gap-2">
        <Skeleton className="h-7 w-28" />
        <Skeleton className="h-7 w-24" />
      </div>
    </div>

    <IssuesSkeleton />
  </div>
);

export default SignalsPageSkeleton;
