import React, { useCallback, useMemo, useRef, useState } from "react";
import { ChartLine, ChevronDown, Plus } from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { ListAction } from "@/components/ui/list-action";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import InsightsViewDialog, {
  InsightsViewDialogMode,
} from "./InsightsViewDialog";
import {
  BuiltInViewItem,
  CustomViewItem,
  InsightsViewOption,
} from "./InsightsViewItems";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import useAppStore from "@/store/AppStore";
import {
  Dashboard,
  DASHBOARD_SCOPE,
  DASHBOARD_TYPE,
  DashboardTemplate,
} from "@/types/dashboard";
import { PROJECT_TEMPLATE_LIST } from "@/lib/dashboard/templates";
import {
  generateDashboardScopeFilter,
  generateDashboardTypeFilter,
} from "@/lib/filters";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";

const CUSTOM_VIEW_ICON = ChartLine;
const CUSTOM_VIEW_ICON_COLOR = "text-chart-orange";

interface InsightsViewSelectorProps {
  value: string | null;
  onChange: (id: string | null) => void;
  onViewCreated?: (dashboardId: string) => void;
  onViewDeleted?: (deletedDashboardId: string) => void;
}

interface DialogState {
  isOpen: boolean;
  mode: InsightsViewDialogMode;
  dashboard?: Dashboard;
}

interface DeleteState {
  isOpen: boolean;
  dashboard?: Dashboard;
}

const getWidgetCount = (dashboard: Dashboard): number => {
  try {
    return dashboard.config.sections.flatMap((s) => s.widgets).length;
  } catch {
    return 0;
  }
};

const formatDashboardDescription = (dashboard: Dashboard): string => {
  const widgetCount = getWidgetCount(dashboard);
  const lastUpdated = dashboard.last_updated_at
    ? formatDate(dashboard.last_updated_at)
    : "";

  return [`${widgetCount} widget${widgetCount !== 1 ? "s" : ""}`, lastUpdated]
    .filter(Boolean)
    .join(", ");
};

const TEMPLATE_OPTIONS: InsightsViewOption[] = PROJECT_TEMPLATE_LIST.map(
  (template) => ({
    value: template.id,
    label: template.name,
    description: template.description,
    icon: template.icon,
    iconColor: template.iconColor,
    isBuiltIn: true,
  }),
);

const buildDashboardOption = (dashboard: Dashboard): InsightsViewOption => ({
  value: dashboard.id,
  label: dashboard.name,
  description: formatDashboardDescription(dashboard),
  icon: CUSTOM_VIEW_ICON,
  iconColor: CUSTOM_VIEW_ICON_COLOR,
  isBuiltIn: false,
});

const InsightsViewSelector: React.FC<InsightsViewSelectorProps> = ({
  value,
  onChange,
  onViewCreated,
  onViewDeleted,
}) => {
  const [isPopoverOpen, setIsPopoverOpen] = useState(false);
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

  const processedFilters = useMemo(() => {
    return [
      ...generateDashboardScopeFilter(DASHBOARD_SCOPE.INSIGHTS),
      ...generateDashboardTypeFilter(DASHBOARD_TYPE.MULTI_PROJECT),
    ];
  }, []);

  const { data: dashboardsData } = useDashboardsList(
    {
      workspaceName,
      filters: processedFilters,
      page: 1,
      size: 1000,
    },
    {
      enabled: Boolean(workspaceName),
    },
  );

  const dashboards = useMemo(
    () => dashboardsData?.content || [],
    [dashboardsData?.content],
  );

  const allOptions = useMemo(
    () => [...TEMPLATE_OPTIONS, ...dashboards.map(buildDashboardOption)],
    [dashboards],
  );

  const searchLower = search.toLowerCase();
  const filteredOptions = allOptions.filter((o) =>
    o.label.toLowerCase().includes(searchLower),
  );
  const filteredBuiltIn = filteredOptions.filter((o) => o.isBuiltIn);
  const filteredCustom = filteredOptions.filter((o) => !o.isBuiltIn);

  const selectedOption = allOptions.find((o) => o.value === value) ?? null;

  const selectedIcon = selectedOption?.icon ?? CUSTOM_VIEW_ICON;
  const selectedIconColor = selectedOption?.iconColor ?? CUSTOM_VIEW_ICON_COLOR;
  const selectedName = selectedOption?.label ?? "Select a view";

  const renderTrigger = () => (
    <TooltipWrapper content={selectedName}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className="max-w-[400px] gap-1.5"
          type="button"
        >
          {React.createElement(selectedIcon, {
            className: cn("size-3.5 shrink-0", selectedIconColor),
          })}
          <span className="comet-body-s-accented truncate">{selectedName}</span>
          <ChevronDown className="ml-1 size-3.5 shrink-0 text-light-slate" />
        </Button>
      </PopoverTrigger>
    </TooltipWrapper>
  );

  const handleSelect = useCallback(
    (id: string) => {
      if (value !== id) {
        onChange(id);
      }
      setIsPopoverOpen(false);
    },
    [onChange, value],
  );

  const handleEditDashboard = useCallback((dashboard: Dashboard) => {
    resetDialogKeyRef.current += 1;
    setDialogState({ isOpen: true, mode: "edit", dashboard });
    setIsPopoverOpen(false);
  }, []);

  const handleDuplicateDashboard = useCallback((dashboard: Dashboard) => {
    resetDialogKeyRef.current += 1;
    setDialogState({ isOpen: true, mode: "clone", dashboard });
    setIsPopoverOpen(false);
  }, []);

  const handleDuplicateTemplate = useCallback((template: DashboardTemplate) => {
    resetDialogKeyRef.current += 1;
    const syntheticDashboard = {
      id: template.id,
      name: template.name,
      description: template.description,
      config: template.config,
      type: DASHBOARD_TYPE.MULTI_PROJECT,
      scope: DASHBOARD_SCOPE.INSIGHTS,
    } as Dashboard;
    setDialogState({
      isOpen: true,
      mode: "clone",
      dashboard: syntheticDashboard,
    });
    setIsPopoverOpen(false);
  }, []);

  const handleDeleteDashboard = useCallback((dashboard: Dashboard) => {
    setDeleteState({ isOpen: true, dashboard });
  }, []);

  const confirmDelete = useCallback(() => {
    if (!deleteState.dashboard) return;
    const deletedId = deleteState.dashboard.id;
    deleteMutate(
      { ids: [deletedId] },
      {
        onSuccess: () => {
          onViewDeleted?.(deletedId);
        },
      },
    );
    setDeleteState({ isOpen: false });
    setIsPopoverOpen(false);
  }, [deleteState.dashboard, deleteMutate, onViewDeleted]);

  const handleCreateNew = useCallback(() => {
    resetDialogKeyRef.current += 1;
    setIsPopoverOpen(false);
    setDialogState({ isOpen: true, mode: "create" });
  }, []);

  const handleDialogCreateSuccess = useCallback(
    (dashboardId: string) => {
      onViewCreated?.(dashboardId);
    },
    [onViewCreated],
  );

  const closeDialog = useCallback((open: boolean) => {
    if (!open) {
      setDialogState({ isOpen: false, mode: "create" });
    }
  }, []);

  const handleOpenChange = useCallback((open: boolean) => {
    setIsPopoverOpen(open);
    if (!open) {
      setSearch("");
    }
  }, []);

  const hasResults = filteredOptions.length > 0;
  const showSeparator = filteredBuiltIn.length > 0 && filteredCustom.length > 0;

  return (
    <>
      <Popover open={isPopoverOpen} onOpenChange={handleOpenChange}>
        {renderTrigger()}

        <PopoverContent
          className="w-[392px] p-1"
          align="start"
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <div onKeyDown={(e) => e.stopPropagation()}>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search"
              variant="ghost"
            />
          </div>
          <Separator className="my-1" />

          <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden">
            {!hasResults ? (
              <div className="flex min-h-24 flex-col items-center justify-center px-6 py-4">
                <div className="comet-body-s text-center text-muted-slate">
                  No search results
                </div>
              </div>
            ) : (
              <>
                {filteredBuiltIn.map((option) => (
                  <BuiltInViewItem
                    key={option.value}
                    option={option}
                    isSelected={value === option.value}
                    onSelect={handleSelect}
                    onDuplicate={() => {
                      const template = PROJECT_TEMPLATE_LIST.find(
                        (t) => t.id === option.value,
                      );
                      if (template) handleDuplicateTemplate(template);
                    }}
                  />
                ))}

                {showSeparator && <Separator className="my-1" />}

                {filteredCustom.map((option) => {
                  const dashboard = dashboards.find(
                    (d) => d.id === option.value,
                  )!;
                  return (
                    <CustomViewItem
                      key={option.value}
                      option={option}
                      isSelected={value === option.value}
                      onSelect={handleSelect}
                      onEdit={() => handleEditDashboard(dashboard)}
                      onDuplicate={() => handleDuplicateDashboard(dashboard)}
                      onDelete={() => handleDeleteDashboard(dashboard)}
                    />
                  );
                })}
              </>
            )}
          </div>

          <Separator className="my-1" />
          <ListAction onClick={handleCreateNew}>
            <Plus className="size-4 shrink-0" />
            Add new
          </ListAction>
        </PopoverContent>
      </Popover>

      <InsightsViewDialog
        key={resetDialogKeyRef.current}
        mode={dialogState.mode}
        dashboard={dialogState.dashboard}
        open={dialogState.isOpen}
        setOpen={closeDialog}
        onCreateSuccess={handleDialogCreateSuccess}
      />

      <ConfirmDialog
        open={deleteState.isOpen}
        setOpen={(open) => setDeleteState({ isOpen: open })}
        onConfirm={confirmDelete}
        title="Delete view"
        description="Are you sure you want to delete this view? This action cannot be undone."
        confirmText="Delete"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default InsightsViewSelector;
