import { useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { StringParam, useQueryParam } from "use-query-params";
import {
  PromptVersion,
  PromptWithLatestVersion,
  PROMPT_VERSION_TYPE,
} from "@/types/prompts";
import { VersionHistoryItem } from "@/v2/pages-shared/version-history/VersionHistoryTimeline";
import usePromptVersionsByIdInfinite from "@/api/prompts/usePromptVersionsByIdInfinite";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";

export interface VersionWithMaybeAuthor extends PromptVersion {
  created_by?: string;
}

export default function usePromptVersionHistory(
  prompt?: PromptWithLatestVersion,
) {
  const [isDiffMenuOpen, setIsDiffMenuOpen] = useState(false);

  const [activeVersionId, setActiveVersionId] = useQueryParam(
    "activeVersionId",
    StringParam,
  );

  const {
    data,
    isLoading: isVersionsLoading,
    hasNextPage,
    isFetching: isFetchingVersions,
    isFetchingNextPage,
    isError: isVersionsError,
    fetchNextPage,
  } = usePromptVersionsByIdInfinite(
    {
      promptId: prompt?.id || "",
      sorting: [{ id: "created_at", desc: true }],
    },
    {
      enabled: !!prompt?.id,
      refetchInterval: 30000,
    },
  );

  const versions = useMemo(
    () =>
      data?.pages.flatMap((p) => p.content) as
        | VersionWithMaybeAuthor[]
        | undefined,
    [data],
  );
  const historyItems = useMemo<VersionHistoryItem[]>(() => {
    if (!versions) return [];
    return versions.map((v) => ({
      id: v.id,
      label: v.version_number ?? v.commit,
      tags: v.tags ?? [],
      description: v.change_description,
      created_at: v.created_at,
      created_by: v.created_by,
      environments: v.environments ?? [],
    }));
  }, [versions]);

  // A deep link to an old version not yet in the loaded pages still renders
  // correctly (fetched independently below), but the sidebar can't highlight
  // it or scroll it into view until its page is loaded — keep paging until
  // it's found (or there's nothing left to load) so the two stay in sync.
  // Must wait for `isFetchingVersions` (not just `isFetchingNextPage`) to go
  // idle: a background refetch of an already-loaded page (e.g. after a
  // version-create invalidation) reports `isFetchingNextPage: false` too,
  // and fetching the next page against that stale intermediate state can
  // request an offset that no longer lines up with the refreshed page,
  // producing an overlapping/duplicate row once both resolve.
  // Also stop on `isVersionsError`: `hasNextPage` reflects the last
  // *successful* page, so a failed fetchNextPage (retry disabled app-wide)
  // never clears it — without this guard the effect re-fires the instant
  // the failed request settles, hammering the backend forever.
  useEffect(() => {
    if (
      activeVersionId &&
      versions &&
      !versions.some((v) => v.id === activeVersionId) &&
      hasNextPage &&
      !isFetchingVersions &&
      !isVersionsError
    ) {
      fetchNextPage();
    }
  }, [
    activeVersionId,
    versions,
    hasNextPage,
    isFetchingVersions,
    isVersionsError,
    fetchNextPage,
  ]);

  // The Diff menu's "Compare against" list only offers whatever pages are
  // already loaded — while it's open, keep paging until every version is
  // available so it doesn't silently omit older, not-yet-loaded versions.
  // See the effect above for why `isVersionsError` is also required.
  useEffect(() => {
    if (
      isDiffMenuOpen &&
      hasNextPage &&
      !isFetchingVersions &&
      !isVersionsError
    ) {
      fetchNextPage();
    }
  }, [
    isDiffMenuOpen,
    hasNextPage,
    isFetchingVersions,
    isVersionsError,
    fetchNextPage,
  ]);

  const effectiveVersionId = useMemo(() => {
    if (activeVersionId) return activeVersionId;
    return prompt?.latest_version?.id ?? versions?.[0]?.id ?? "";
  }, [activeVersionId, versions, prompt?.latest_version?.id]);

  // The paginated list response already carries full version fields, so if
  // the target version is among the loaded pages, reuse it instead of
  // issuing a redundant by-id request for data we already have.
  const versionFromList = useMemo(
    () => versions?.find((v) => v.id === effectiveVersionId),
    [versions, effectiveVersionId],
  );

  const { data: fetchedActiveVersion, isLoading: isActiveVersionLoading } =
    usePromptVersionById(
      { versionId: effectiveVersionId },
      {
        enabled: !!effectiveVersionId && !versionFromList,
        placeholderData: keepPreviousData,
      },
    );

  // A stale or crafted activeVersionId query param can reference a version
  // belonging to a different prompt (it's fetched independently of the
  // current prompt's own version list) — never render foreign content here.
  // Also reject masks: the single-version-by-id lookup isn't scoped to
  // version_type the way the paginated list is, so a mask's id (same
  // prompt) would otherwise pass the ownership check and render as a real
  // version — and "Deploy to" on it would then 400 server-side. Neither
  // check applies to `versionFromList`: it comes straight from the current
  // prompt's own (mask-filtered) version list.
  const activeVersion: VersionWithMaybeAuthor | undefined =
    versionFromList ??
    (fetchedActiveVersion &&
    prompt &&
    fetchedActiveVersion.prompt_id === prompt.id &&
    fetchedActiveVersion.version_type !== PROMPT_VERSION_TYPE.MASK
      ? fetchedActiveVersion
      : prompt?.latest_version);

  const activeVersionLabel =
    activeVersion?.version_number ?? activeVersion?.commit ?? "";

  return {
    activeVersionId,
    setActiveVersionId,
    versions,
    historyItems,
    effectiveVersionId,
    versionFromList,
    fetchedActiveVersion,
    activeVersion,
    activeVersionLabel,
    isVersionsLoading,
    isActiveVersionLoading,
    hasNextPage,
    isFetchingNextPage,
    isFetchingVersions,
    isVersionsError,
    fetchNextPage,
    isDiffMenuOpen,
    setIsDiffMenuOpen,
  };
}
