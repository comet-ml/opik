import React, { useCallback, useMemo, useRef, useState } from "react";
import { ChevronDown, Plus } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import AddEditCloneDashboardDialog, {
  DashboardDialogMode,
} from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { isTemplateId } from "@/lib/dashboard/utils";
import { Dashboard } from "@/types/dashboard";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import { TemplateItem } from "./TemplateItem";
import { DashboardItem } from "./DashboardItem";
import { TriggerContent } from "./TriggerContent";

const DEFAULT_LOADED_DASHBOARDS = 1000;
const MAX_LOADED_DASHBOARDS = 10000;

interface DashboardSelectBoxProps {
  value: string | null;
  onChange: (id: string | null) => void;
  disabled?: boolean;
  buttonClassName?: string;
  onDashboardCreated?: (dashboardId: string) => void;
  onDashboardDeleted?: (deletedDashboardId: string) => void;
  defaultProjectId?: string;
}

interface DialogState {
  isOpen: boolean;
  mode: DashboardDialogMode;
  dashboard?: Dashboard;
}

interface DeleteState {
  isOpen: boolean;
  dashboard?: Dashboard;
}

const DashboardSelectBox: React.FC<DashboardSelectBoxProps> = ({
  value,
  onChange,
  disabled = false,
  buttonClassName,
  onDashboardCreated,
  onDashboardDeleted,
  defaultProjectId,
}) => {
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [search, setSearch] = useState("");
  const resetDialogKeyRef = useRef(0);

  const [dialogState, setDialogState] = useState<DialogState>({
    isOpen: false,
    mode: "create",
  });

  const [deleteState, setDeleteState] = useState<DeleteState>({
    isOpen: false,
  });

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const deleteDashboardMutation = useDashboardBatchDeleteMutation();

  const { data: dashboardsData } = useDashboardsList(
    {
      workspaceName,
      page: 1,
      size: !isLoadedMore ? DEFAULT_LOADED_DASHBOARDS : MAX_LOADED_DASHBOARDS,
    },
    {
      enabled: Boolean(workspaceName),
    },
  );

  const dashboards = useMemo(
    () => dashboardsData?.content || [],
    [dashboardsData?.content],
  );
  const dashboardTotal = dashboardsData?.total;

  const filteredTemplates = useMemo(() => {
    return TEMPLATE_LIST.filter((template) =>
      template.title.toLowerCase().includes(search.toLowerCase()),
    );
  }, [search]);

  const filteredDashboards = useMemo(() => {
    return dashboards.filter((dashboard) =>
      dashboard.name.toLowerCase().includes(search.toLowerCase()),
    );
  }, [dashboards, search]);

  const selectedItem = useMemo(() => {
    if (isTemplateId(value)) {
      return TEMPLATE_LIST.find((t) => t.id === value);
    }
    return dashboards.find((d) => d.id === value);
  }, [value, dashboards]);

  const handleChangeDashboardId = useCallback(
    (id: string) => {
      if (value !== id) {
        onChange(id);
        setIsDropdownOpen(false);
      }
    },
    [onChange, value],
  );

  const handleEditDashboard = useCallback((dashboard: Dashboard) => {
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    setDialogState({ isOpen: true, mode: "edit", dashboard });
    setIsDropdownOpen(false);
  }, []);

  const handleDuplicateDashboard = useCallback((dashboard: Dashboard) => {
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    setDialogState({ isOpen: true, mode: "clone", dashboard });
    setIsDropdownOpen(false);
  }, []);

  const handleDeleteDashboard = useCallback((dashboard: Dashboard) => {
    setDeleteState({ isOpen: true, dashboard });
  }, []);

  const confirmDelete = useCallback(async () => {
    if (!deleteState.dashboard) return;

    const deletedDashboardId = deleteState.dashboard.id;

    deleteDashboardMutation.mutate(
      {
        ids: [deletedDashboardId],
      },
      {
        onSuccess: () => {
          if (onDashboardDeleted) {
            onDashboardDeleted(deletedDashboardId);
          }
        },
      },
    );

    setDeleteState({ isOpen: false });
    setIsDropdownOpen(false);
  }, [deleteState.dashboard, deleteDashboardMutation, onDashboardDeleted]);

  const handleCreateNew = useCallback(() => {
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    setIsDropdownOpen(false);
    setDialogState({ isOpen: true, mode: "create" });
  }, []);

  const handleDialogCreateSuccess = useCallback(
    (dashboardId: string) => {
      if (onDashboardCreated) {
        onDashboardCreated(dashboardId);
      }
    },
    [onDashboardCreated],
  );

  const closeDialog = useCallback((open: boolean) => {
    if (!open) {
      setDialogState({ isOpen: false, mode: "create" });
    }
  }, []);

  const hasResults =
    filteredTemplates.length > 0 || filteredDashboards.length > 0;
  const showSeparator =
    filteredTemplates.length > 0 && filteredDashboards.length > 0;
  const shouldShowLoadMore =
    (dashboardTotal || 0) > DEFAULT_LOADED_DASHBOARDS && !isLoadedMore;

  return (
    <>
      <DropdownMenu open={isDropdownOpen} onOpenChange={setIsDropdownOpen}>
        <DropdownMenuTrigger asChild>
          <Button
            className={cn("group justify-between px-3", buttonClassName, {
              "disabled:cursor-not-allowed disabled:border-input disabled:bg-muted-disabled disabled:text-muted-gray disabled:placeholder:text-muted-gray hover:disabled:shadow-none":
                disabled,
            })}
            size="sm"
            variant="outline"
            disabled={disabled}
            type="button"
          >
            <TriggerContent selectedItem={selectedItem} value={value} />
            <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate group-disabled:text-muted-gray" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          className="w-[300px] max-w-[300px] p-0"
          align="end"
          onCloseAutoFocus={(e) => e.preventDefault()}
        >
          <div className="px-1 pt-1">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search"
              variant="ghost"
            />
            <DropdownMenuSeparator className="mt-1" />
          </div>

          <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden px-1">
            {!hasResults ? (
              <div className="flex min-h-24 flex-col items-center justify-center px-6 py-4">
                <div className="comet-body-s text-center text-muted-slate">
                  No search results
                </div>
              </div>
            ) : (
              <>
                {filteredTemplates.map((template) => (
                  <TemplateItem
                    key={template.id}
                    template={template}
                    isSelected={value === template.id}
                    onSelect={handleChangeDashboardId}
                  />
                ))}

                {showSeparator && (
                  <>
                    <DropdownMenuSeparator className="my-1.5" />
                    <DropdownMenuLabel className="comet-body-xs px-4 py-1.5 text-muted-slate">
                      YOUR DASHBOARDS
                    </DropdownMenuLabel>
                  </>
                )}

                {filteredDashboards.map((dashboard) => (
                  <DashboardItem
                    key={dashboard.id}
                    dashboard={dashboard}
                    isSelected={value === dashboard.id}
                    onSelect={handleChangeDashboardId}
                    onEdit={handleEditDashboard}
                    onDuplicate={handleDuplicateDashboard}
                    onDelete={handleDeleteDashboard}
                  />
                ))}
              </>
            )}
          </div>

          {shouldShowLoadMore && (
            <div className="flex flex-wrap items-center justify-between border-t border-border px-5">
              <div className="comet-body-s text-light-slate">
                {`Showing first ${DEFAULT_LOADED_DASHBOARDS} items.`}
              </div>
              <Button
                variant="link"
                onClick={() => setIsLoadedMore(true)}
                type="button"
              >
                Load more
              </Button>
            </div>
          )}

          <div className="px-1">
            <DropdownMenuSeparator className="my-1" />
            <div
              className="flex h-10 cursor-pointer items-center rounded-md px-4 hover:bg-primary-foreground"
              onClick={handleCreateNew}
            >
              <div className="comet-body-s flex items-center gap-2 text-primary">
                <Plus className="size-3.5 shrink-0" />
                <span>Create new dashboard</span>
              </div>
            </div>
          </div>
        </DropdownMenuContent>
      </DropdownMenu>

      <AddEditCloneDashboardDialog
        key={resetDialogKeyRef.current}
        mode={dialogState.mode}
        dashboard={dialogState.dashboard}
        open={dialogState.isOpen}
        setOpen={closeDialog}
        onCreateSuccess={handleDialogCreateSuccess}
        navigateOnCreate={false}
        defaultProjectId={defaultProjectId}
      />

      <ConfirmDialog
        open={deleteState.isOpen}
        setOpen={(open) => setDeleteState({ isOpen: open })}
        onConfirm={confirmDelete}
        title="Delete dashboard"
        description={`Are you sure you want to delete "${deleteState.dashboard?.name}"? This action cannot be undone.`}
        confirmText="Delete"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default DashboardSelectBox;
