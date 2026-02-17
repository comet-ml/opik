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
import { ListAction } from "@/components/ui/list-action";
import { Separator } from "@/components/ui/separator";
import AddEditCloneDashboardDialog, {
  DashboardDialogMode,
} from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { isTemplateId } from "@/lib/dashboard/utils";
import { Dashboard, DashboardTemplate } from "@/types/dashboard";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import { SelectItem } from "./SelectItem";
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
  defaultExperimentIds?: string[];
  templates?: DashboardTemplate[];
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
  defaultExperimentIds,
  templates = TEMPLATE_LIST,
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
  const { mutate: deleteMutate } = useDashboardBatchDeleteMutation();

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
    return templates.filter((template) =>
      template.name.toLowerCase().includes(search.toLowerCase()),
    );
  }, [search, templates]);

  const filteredDashboards = useMemo(() => {
    return dashboards.filter((dashboard) =>
      dashboard.name.toLowerCase().includes(search.toLowerCase()),
    );
  }, [dashboards, search]);

  const selectedItem = useMemo(() => {
    if (isTemplateId(value)) {
      return templates.find((t) => t.id === value);
    }
    return dashboards.find((d) => d.id === value);
  }, [value, dashboards, templates]);

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

    deleteMutate(
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
  }, [deleteState.dashboard, deleteMutate, onDashboardDeleted]);

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

  const handleOpenChange = useCallback((open: boolean) => {
    setIsDropdownOpen(open);
    if (!open) {
      setSearch("");
    }
  }, []);

  return (
    <>
      <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
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
            <TriggerContent selectedItem={selectedItem} />
            <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate group-disabled:text-muted-gray" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          className="w-[300px] max-w-[300px] p-0 pt-12"
          align="end"
          onCloseAutoFocus={(e) => e.preventDefault()}
        >
          <div
            className="absolute inset-x-1 top-1 h-11"
            onKeyDown={(e) => e.stopPropagation()}
          >
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search"
              variant="ghost"
            />
            <Separator className="mt-1" />
          </div>

          <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden p-1">
            {!hasResults ? (
              <div className="flex min-h-24 flex-col items-center justify-center px-6 py-4">
                <div className="comet-body-s text-center text-muted-slate">
                  No search results
                </div>
              </div>
            ) : (
              <>
                {filteredTemplates.length > 0 && (
                  <>
                    <DropdownMenuLabel>Prebuilt dashboards</DropdownMenuLabel>
                    {filteredTemplates.map((template) => (
                      <SelectItem
                        key={template.id}
                        id={template.id}
                        name={template.name}
                        description={template.description}
                        icon={template.icon}
                        iconColor={template.iconColor}
                        isSelected={value === template.id}
                        onSelect={handleChangeDashboardId}
                      />
                    ))}
                  </>
                )}

                {showSeparator && <DropdownMenuSeparator className="my-1.5" />}

                {filteredDashboards.length > 0 && (
                  <>
                    <DropdownMenuLabel>Your dashboards</DropdownMenuLabel>
                    {filteredDashboards.map((dashboard) => (
                      <SelectItem
                        key={dashboard.id}
                        id={dashboard.id}
                        name={dashboard.name}
                        description={dashboard.description}
                        isSelected={value === dashboard.id}
                        onSelect={handleChangeDashboardId}
                        dashboard={dashboard}
                        onEdit={handleEditDashboard}
                        onDuplicate={handleDuplicateDashboard}
                        onDelete={handleDeleteDashboard}
                      />
                    ))}
                  </>
                )}
              </>
            )}
          </div>

          {shouldShowLoadMore && (
            <>
              <Separator />
              <div className="flex flex-wrap items-center justify-between px-5 py-2">
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
            </>
          )}

          <Separator className="my-1" />
          <ListAction onClick={handleCreateNew}>
            <Plus className="size-3.5 shrink-0" />
            Add new
          </ListAction>
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
        defaultExperimentIds={defaultExperimentIds}
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
