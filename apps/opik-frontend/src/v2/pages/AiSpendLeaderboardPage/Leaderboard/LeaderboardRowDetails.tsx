import React from "react";
import { FolderGit2 } from "lucide-react";
import AiUsageBreakdown from "@/v2/pages-shared/AiUsageBreakdown/AiUsageBreakdown";
import LaneSpendPreviewHoverCard from "@/v2/pages-shared/AiUsageLanePreview/LaneSpendPreviewHoverCard";

interface LeaderboardRowDetailsProps {
  userUuid: string;
  repositories: string[];
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  activeLaneKey: string | null;
  onViewLaneDetails: (laneKey: string) => void;
}

const MAX_VISIBLE_REPOS = 8;

const LeaderboardRowDetails: React.FC<LeaderboardRowDetailsProps> = ({
  userUuid,
  repositories,
  projectName,
  intervalStart,
  intervalEnd,
  activeLaneKey,
  onViewLaneDetails,
}) => {
  const visibleRepos = repositories.slice(0, MAX_VISIBLE_REPOS);
  const overflow = repositories.length - visibleRepos.length;

  return (
    <div className="flex flex-col gap-4 border-t bg-soft-background p-4">
      {repositories.length > 0 && (
        <div className="flex flex-col gap-2">
          <span className="comet-body-xs text-muted-slate">
            Active repositories
          </span>
          <div className="flex flex-wrap items-center gap-2">
            {visibleRepos.map((repo) => (
              <span
                key={repo}
                className="comet-body-xs flex items-center gap-1.5 rounded-md border bg-background px-2 py-1 text-foreground"
              >
                <FolderGit2 className="size-3.5 shrink-0 text-light-slate" />
                {repo}
              </span>
            ))}
            {overflow > 0 && (
              <span className="comet-body-xs text-muted-slate">
                +{overflow}
              </span>
            )}
          </div>
        </div>
      )}

      <AiUsageBreakdown
        compact
        userUuid={userUuid}
        projectName={projectName}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        onLaneClick={onViewLaneDetails}
        activeLaneKey={activeLaneKey}
        renderLaneWrapper={(lane, card) =>
          lane.hasBreakdown ? (
            <LaneSpendPreviewHoverCard
              laneKey={lane.key}
              projectName={projectName}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              userUuid={userUuid}
              onViewAll={onViewLaneDetails}
            >
              {card}
            </LaneSpendPreviewHoverCard>
          ) : (
            card
          )
        }
      />
    </div>
  );
};

export default LeaderboardRowDetails;
