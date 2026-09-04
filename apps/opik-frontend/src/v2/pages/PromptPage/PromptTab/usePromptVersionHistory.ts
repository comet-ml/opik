import { useEffect, useMemo, useRef, useState } from "react";
import { keepPreviousData, useQueryClient } from "@tanstack/react-query";
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
  const [isDeployMenuOpen, setIsDeployMenuOpen] = useState(false);

  const [activeVersionId, setActiveVersionId] = useQueryParam(
    "activeVersionId",
    StringParam,
  );

  const queryClient = useQueryClient();

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
      // No refetchInterval, and refetchOnWindowFocus explicitly off:
      // useInfiniteQuery refetches every already-loaded page sequentially on
      // either trigger, and the Diff/Deploy menus deliberately load every
      // page for large prompts — polling or refocus-refetching that
      // unconditionally would multiply request volume by however many pages
      // got loaded that session. Instead, the cheap `prompt` query below
      // polls `version_count` and this list only refetches (own mutations
      // aside) when that actually changes.
      refetchOnWindowFocus: false,
    },
  );

  // `prompt` is polled (see PromptPage) so `version_count` changing is a
  // cheap signal that some version was added/removed elsewhere — only then
  // do we pay for the expensive full-history refetch.
  const versionCountRef = useRef(prompt?.version_count);
  useEffect(() => {
    if (
      prompt?.id &&
      prompt.version_count !== undefined &&
      versionCountRef.current !== undefined &&
      prompt.version_count !== versionCountRef.current
    ) {
      queryClient.invalidateQueries({
        predicate: (query) =>
          query.queryKey[0] === "prompt-versions" &&
          typeof query.queryKey[1] === "object" &&
          query.queryKey[1] !== null &&
          "promptId" in query.queryKey[1] &&
          query.queryKey[1].promptId === prompt.id,
      });
    }
    versionCountRef.current = prompt?.version_count;
  }, [prompt?.id, prompt?.version_count, queryClient]);

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
  const isChasingDeepLink =
    !!activeVersionId &&
    !!versions &&
    !versions.some((v) => v.id === activeVersionId);

  // The Diff and Deploy menus each only offer whatever pages are already
  // loaded — while either is open, keep paging until every version is
  // available so they don't silently omit older, not-yet-loaded versions.
  //
  // All three reasons to keep paging are combined into one effect (rather
  // than one per reason) so at most one `fetchNextPage()` call happens per
  // render: with separate effects, two whose conditions both flip true in
  // the same render (e.g. a deep-link chase while the Diff menu is opened)
  // would both fire before either sees the other's in-flight fetch, and
  // `fetchNextPage`'s default `cancelRefetch: true` would cancel the first
  // request for a duplicate of the same page.
  //
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
      (isChasingDeepLink || isDiffMenuOpen || isDeployMenuOpen) &&
      hasNextPage &&
      !isFetchingVersions &&
      !isVersionsError
    ) {
      fetchNextPage();
    }
  }, [
    isChasingDeepLink,
    isDiffMenuOpen,
    isDeployMenuOpen,
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
    isDeployMenuOpen,
    setIsDeployMenuOpen,
  };
}
