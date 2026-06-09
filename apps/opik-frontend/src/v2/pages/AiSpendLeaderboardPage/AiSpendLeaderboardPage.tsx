import React, { useCallback, useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Skeleton } from "@/ui/skeleton";
import { useToast } from "@/ui/use-toast";
import { getSpendInterval, SpendWindow } from "@/lib/aiSpend";
import { useAiSpend } from "@/contexts/AiSpendContext";
import useAiSpendUsers, { SpendUserRow } from "@/api/ai-spend/useAiSpendUsers";
import AiUsageLaneDetailsPanel from "@/v2/pages-shared/AiUsageLaneDetails/AiUsageLaneDetailsPanel";
import LeaderboardToolbar from "./Leaderboard/LeaderboardToolbar";
import LeaderboardRow from "./Leaderboard/LeaderboardRow";
import { LeaderboardSortKey } from "./Leaderboard/LeaderboardSortSelect";

const FETCH_SIZE = 1000;
const ROW_COLLAPSED_HEIGHT = 62;
const ROW_EXPANDED_HEIGHT = 460;
const VIRTUAL_OVERSCAN = 6;

const sortValue = (row: SpendUserRow, key: LeaderboardSortKey): number => {
  if (key === "total_estimated_cost") return row.total_estimated_cost ?? 0;
  return row[key] ?? 0;
};

const getScrollParent = (el: HTMLElement | null): HTMLElement | null => {
  let node = el?.parentElement ?? null;
  while (node) {
    const overflowY = getComputedStyle(node).overflowY;
    if (
      overflowY === "auto" ||
      overflowY === "scroll" ||
      overflowY === "overlay"
    ) {
      return node;
    }
    node = node.parentElement;
  }
  return null;
};

const AiSpendLeaderboardPage: React.FC = () => {
  const { toast } = useToast();
  const { projectName } = useAiSpend();
  const [windowDays, setWindowDays] = useState<SpendWindow>(30);
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState<LeaderboardSortKey>("total_estimated_cost");
  const [expandedUuid, setExpandedUuid] = useState<string | null>(null);
  const [detailsLaneKey, setDetailsLaneKey] = useState<string | null>(null);

  const { intervalStart, intervalEnd } = useMemo(
    () => getSpendInterval(windowDays),
    [windowDays],
  );

  const { data, isPending } = useAiSpendUsers(
    {
      projectName,
      intervalStart,
      intervalEnd,
      page: 1,
      size: FETCH_SIZE,
    },
    { placeholderData: keepPreviousData },
  );

  const users = useMemo(() => data?.content ?? [], [data]);
  const totalUsers = data?.total ?? 0;

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return users;
    return users.filter(
      (user) =>
        user.user_email?.toLowerCase().includes(query) ||
        user.user_display_name?.toLowerCase().includes(query),
    );
  }, [users, search]);

  const sorted = useMemo(
    () => [...filtered].sort((a, b) => sortValue(b, sort) - sortValue(a, sort)),
    [filtered, sort],
  );

  useEffect(() => {
    if (!isPending && totalUsers > users.length) {
      toast({
        variant: "destructive",
        title: "Showing the top users only",
        description: `Only the first ${users.length.toLocaleString()} of ${totalUsers.toLocaleString()} users are shown for this period.`,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  const toggle = (uuid: string) => {
    setDetailsLaneKey(null);
    setExpandedUuid((prev) => (prev === uuid ? null : uuid));
  };

  const selectedUser =
    sorted.find((user) => user.user_uuid === expandedUuid) ?? null;

  const [scrollElement, setScrollElement] = useState<HTMLElement | null>(null);
  const [scrollMargin, setScrollMargin] = useState(0);
  const listRef = useCallback((node: HTMLDivElement | null) => {
    if (node) {
      setScrollMargin(node.offsetTop);
      setScrollElement(getScrollParent(node));
    }
  }, []);

  const virtualizer = useVirtualizer({
    count: sorted.length,
    getScrollElement: () => scrollElement,
    estimateSize: (index) =>
      sorted[index]?.user_uuid === expandedUuid
        ? ROW_EXPANDED_HEIGHT
        : ROW_COLLAPSED_HEIGHT,
    overscan: VIRTUAL_OVERSCAN,
    getItemKey: (index) => sorted[index].user_uuid,
    scrollMargin,
  });

  useEffect(() => {
    virtualizer.measure();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expandedUuid]);

  const renderContent = () => {
    if (isPending) {
      return (
        <div className="flex flex-col gap-1.5">
          {Array.from({ length: 8 }).map((_, index) => (
            <Skeleton key={index} className="h-14 w-full rounded-md" />
          ))}
        </div>
      );
    }

    if (sorted.length === 0) {
      return (
        <div className="comet-body-s flex h-40 items-center justify-center text-muted-slate">
          No users in this period.
        </div>
      );
    }

    const items = virtualizer.getVirtualItems();

    return (
      <div
        ref={listRef}
        className="relative"
        style={{ height: virtualizer.getTotalSize() }}
      >
        <div
          className="absolute inset-x-0 top-0"
          style={{
            transform: `translateY(${
              (items[0]?.start ?? 0) - virtualizer.options.scrollMargin
            }px)`,
          }}
        >
          {items.map((virtualRow) => {
            const row = sorted[virtualRow.index];
            return (
              <div
                key={row.user_uuid}
                data-index={virtualRow.index}
                ref={virtualizer.measureElement}
                className="pb-1.5"
              >
                <LeaderboardRow
                  row={row}
                  rank={virtualRow.index + 1}
                  expanded={expandedUuid === row.user_uuid}
                  dimmed={
                    expandedUuid !== null && expandedUuid !== row.user_uuid
                  }
                  onToggle={() => toggle(row.user_uuid)}
                  projectName={projectName}
                  intervalStart={intervalStart}
                  intervalEnd={intervalEnd}
                  detailsLaneKey={detailsLaneKey}
                  onViewLaneDetails={setDetailsLaneKey}
                />
              </div>
            );
          })}
        </div>
      </div>
    );
  };

  return (
    <div className="pt-6">
      <h1 className="comet-title-l mb-4 truncate break-words text-foreground">
        User leaderboard
      </h1>

      <LeaderboardToolbar
        search={search}
        onSearchChange={setSearch}
        sort={sort}
        onSortChange={setSort}
        windowDays={windowDays}
        onWindowChange={setWindowDays}
      />

      {renderContent()}

      <AiUsageLaneDetailsPanel
        laneKey={detailsLaneKey}
        projectName={projectName}
        defaultWindow={windowDays}
        userUuid={selectedUser?.user_uuid}
        userName={selectedUser?.user_email}
        showRecommendations={false}
        onClose={() => setDetailsLaneKey(null)}
      />
    </div>
  );
};

export default AiSpendLeaderboardPage;
