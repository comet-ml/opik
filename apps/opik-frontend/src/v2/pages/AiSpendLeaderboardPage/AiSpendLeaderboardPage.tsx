import React, { useCallback, useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Skeleton } from "@/ui/skeleton";
import { getSpendInterval, SpendWindow } from "@/lib/aiSpend";
import { useAiSpend } from "@/contexts/AiSpendContext";
import useAiSpendUsers from "@/api/ai-spend/useAiSpendUsers";
import AiUsageLaneDetailsPanel from "@/v2/pages-shared/AiUsageLaneDetails/AiUsageLaneDetailsPanel";
import LeaderboardToolbar from "./Leaderboard/LeaderboardToolbar";
import LeaderboardRow from "./Leaderboard/LeaderboardRow";
import { LeaderboardSortKey } from "./Leaderboard/LeaderboardSortSelect";

const FETCH_SIZE = 1000;
const ROW_COLLAPSED_HEIGHT = 62;
const ROW_EXPANDED_HEIGHT = 460;
const VIRTUAL_OVERSCAN = 6;

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

  const sorting = useMemo(() => [{ id: sort, desc: true }], [sort]);

  const { data, isPending } = useAiSpendUsers(
    {
      projectName,
      intervalStart,
      intervalEnd,
      page: 1,
      size: FETCH_SIZE,
      name: search,
      sorting,
    },
    { placeholderData: keepPreviousData },
  );

  const users = useMemo(() => data?.content ?? [], [data]);
  const totalUsers = data?.total ?? 0;

  const isTruncated = !isPending && totalUsers > users.length;

  useEffect(() => {
    setExpandedUuid(null);
    setDetailsLaneKey(null);
  }, [search, sort, windowDays]);

  const toggle = (uuid: string) => {
    setDetailsLaneKey(null);
    setExpandedUuid((prev) => (prev === uuid ? null : uuid));
  };

  const selectedUser = useMemo(
    () => users.find((user) => user.user_uuid === expandedUuid) ?? null,
    [users, expandedUuid],
  );

  const [scrollElement, setScrollElement] = useState<HTMLElement | null>(null);
  const [scrollMargin, setScrollMargin] = useState(0);
  const listRef = useCallback((node: HTMLDivElement | null) => {
    if (node) {
      setScrollMargin(node.offsetTop);
      setScrollElement(getScrollParent(node));
    }
  }, []);

  const virtualizer = useVirtualizer({
    count: users.length,
    getScrollElement: () => scrollElement,
    estimateSize: (index) =>
      users[index]?.user_uuid === expandedUuid
        ? ROW_EXPANDED_HEIGHT
        : ROW_COLLAPSED_HEIGHT,
    overscan: VIRTUAL_OVERSCAN,
    getItemKey: (index) => users[index].user_uuid,
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

    if (users.length === 0) {
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
            const row = users[virtualRow.index];
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

      {isTruncated && (
        <div className="comet-body-s mb-3 rounded-md border border-border bg-secondary px-3 py-2 text-muted-slate">
          Showing the top {users.length.toLocaleString()} of{" "}
          {totalUsers.toLocaleString()} users for this period.
        </div>
      )}

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
