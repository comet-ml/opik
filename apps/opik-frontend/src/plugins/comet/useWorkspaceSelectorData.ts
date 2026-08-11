import { useCallback, useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import sortBy from "lodash/sortBy";

import { useToast } from "@/ui/use-toast";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useUser from "@/plugins/comet/useUser";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useOrganizationWorkspacesPage from "@/plugins/comet/useOrganizationWorkspacesPage";
import useRecentWorkspaces from "@/plugins/comet/useRecentWorkspaces";
import useAppStore from "@/store/AppStore";
import {
  Workspace,
  Organization,
  ORGANIZATION_ROLE_TYPE,
} from "@/plugins/comet/types";
import { isAiSpendWorkspace } from "@/plugins/comet/lib/aiSpend";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import { buildUrl } from "@/plugins/comet/utils";
import { postWorkspacePointRead } from "@/plugins/comet/lib/workspacePointRead";

const SEARCH_DEBOUNCE_MS = 300;

const useWorkspaceSelectorData = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [search, setSearch] = useState("");
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [isOrgSubmenuOpen, setIsOrgSubmenuOpen] = useState(false);

  const { data: user } = useUser();
  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });
  const currentOrganization = useCurrentOrganization();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const organizationId = currentOrganization?.id ?? "";

  // Identity of the active workspace via the point read (shared cache with bootstrap), so the recency
  // record carries id + organizationId and the switcher can render recents without a list download.
  const activeWorkspace = useWorkspace();
  const { recentWorkspaces, recordVisit } = useRecentWorkspaces();

  // Debounce the search term so each keystroke does not fire a server request.
  const [debouncedSearch, setDebouncedSearch] = useState("");
  useEffect(() => {
    const timeout = setTimeout(
      () => setDebouncedSearch(search),
      SEARCH_DEBOUNCE_MS,
    );
    return () => clearTimeout(timeout);
  }, [search]);
  const trimmedSearch = debouncedSearch.trim();
  const isSearching = trimmedSearch.length > 0;

  // One bounded page per slice from the visibility-scoped endpoint; `search` reaches the server.
  const {
    data: pagedData,
    isLoading: isPageLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useOrganizationWorkspacesPage(
    { organizationId, search: trimmedSearch || undefined },
    {
      enabled: !!user?.loggedIn && !!organizationId,
      // Keep the previous slice on screen while a new search term loads, so the trigger and list do
      // not flash a skeleton on every keystroke.
      placeholderData: keepPreviousData,
    },
  );

  // Mark the active workspace as recently visited (with identity) so recency ordering reflects
  // arrivals via direct URL, not only in-menu switches.
  useEffect(() => {
    if (activeWorkspace) recordVisit(activeWorkspace);
  }, [activeWorkspace, recordVisit]);

  const serverWorkspaces = useMemo<Workspace[]>(
    () => (pagedData?.pages ?? []).flatMap((page) => page.data),
    [pagedData],
  );
  const total = pagedData?.pages?.[0]?.total ?? 0;

  // Recently visited, scoped to the current organization and stripped of the personal "default" and
  // reserved AI-spend workspaces (the endpoint hides them; recents are local so we filter here).
  const recents = useMemo<Workspace[]>(
    () =>
      recentWorkspaces
        .map<Workspace>((workspace) => ({
          workspaceId: workspace.workspaceId,
          workspaceName: workspace.workspaceName,
          organizationId: workspace.organizationId,
          default: false,
        }))
        .filter(
          (workspace) =>
            workspace.organizationId === organizationId &&
            workspace.workspaceName !== DEFAULT_WORKSPACE_NAME &&
            !isAiSpendWorkspace(workspace),
        ),
    [recentWorkspaces, organizationId],
  );

  const handleOpenChange = useCallback(
    (open: boolean) => {
      if (!open && isOrgSubmenuOpen) {
        setIsOrgSubmenuOpen(false);
        return;
      }

      setIsDropdownOpen(open);
      if (!open) {
        setSearch("");
      }
    },
    [isOrgSubmenuOpen],
  );

  const handleChangeWorkspace = useCallback(
    (newWorkspace: Workspace) => {
      recordVisit(newWorkspace);
      navigate({
        to: "/$workspaceName",
        params: { workspaceName: newWorkspace.workspaceName },
      });
    },
    [navigate, recordVisit],
  );

  // Where to land is a point read, not a scan of a downloaded list: the backend picks the caller's
  // default workspace in the target organization (or, for an admin, the organization's first), and
  // answers 404 -> null when the caller has none there.
  const handleChangeOrganization = useCallback(
    async (newOrganization: Organization) => {
      const landing = await postWorkspacePointRead(
        "/workspaces/retrieve-landing",
        { organizationId: newOrganization.id },
      );

      if (!landing) {
        toast({
          description: `You are not part of any workspaces in ${newOrganization.name}, please ask to be invited to one`,
          variant: "destructive",
        });
        return;
      }

      recordVisit(landing);
      navigate({
        to: "/$workspaceName",
        params: { workspaceName: landing.workspaceName },
      });
    },
    [navigate, toast, recordVisit],
  );

  const handleOrgSettingsClick = useCallback(() => {
    if (currentOrganization && workspaceName) {
      window.location.href = buildUrl(
        `organizations/${currentOrganization.id}`,
        workspaceName,
      );
    }
  }, [currentOrganization, workspaceName]);

  const sortedOrganizations = useMemo(() => {
    if (!organizations) return [];
    return sortBy(organizations, "name");
  }, [organizations]);

  const hasMultipleOrganizations = organizations && organizations.length > 1;
  const hasWorkspaces = total > 0 || recents.length > 0;
  const shouldShowDropdown = hasWorkspaces || hasMultipleOrganizations;
  const isOrgAdmin = currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  return {
    user,
    workspaceName,
    currentOrganization,
    // Only the first-slice load blocks the menu; loading more is inline.
    isLoading: isPageLoading,
    organizations,

    search,
    setSearch,
    isSearching,
    isDropdownOpen,
    setIsDropdownOpen,
    isOrgSubmenuOpen,
    setIsOrgSubmenuOpen,

    handleOpenChange,
    handleChangeWorkspace,
    handleChangeOrganization,
    handleOrgSettingsClick,

    recents,
    serverWorkspaces,
    total,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,

    sortedOrganizations,

    shouldShowDropdown,
    hasMultipleOrganizations,
    isOrgAdmin,
  };
};

export default useWorkspaceSelectorData;
