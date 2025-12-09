import React, { useCallback, useMemo, useState } from "react";
import { LayoutDashboard, Plus } from "lucide-react";

import { Separator } from "@/components/ui/separator";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import AddEditCloneDashboardDialog from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_DASHBOARDS = 1000;
const MAX_LOADED_DASHBOARDS = 10000;

export const DashboardEmptyState = () => {
  return (
    <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
      <div className="comet-body-s-accented pb-1 text-foreground">
        No dashboards available
      </div>
      <div className="comet-body-s text-muted-slate">
        Create a dashboard to get started.
      </div>
    </div>
  );
};

interface DashboardSelectBoxProps {
  value: string | null;
  onChange: (id: string | null) => void;
  disabled?: boolean;
  buttonClassName?: string;
}

const DashboardSelectBox: React.FC<DashboardSelectBoxProps> = ({
  value,
  onChange,
  disabled = false,
  buttonClassName,
}) => {
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [isDashboardDialogOpen, setIsDashboardDialogOpen] = useState(false);
  const [isDashboardDropdownOpen, setIsDashboardDropdownOpen] = useState(false);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: dashboardsData, isLoading: isLoadingDashboards } =
    useDashboardsList(
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

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const dashboardOptions = useMemo(() => {
    return dashboards.map((dashboard) => ({
      label: dashboard.name,
      value: dashboard.id,
      description: dashboard.description,
      action: {
        href: `/${workspaceName}/dashboards/${dashboard.id}`,
      },
    }));
  }, [dashboards, workspaceName]);

  const handleChangeDashboardId = useCallback(
    (id: string | null) => {
      if (value !== id) {
        onChange(id);
      }
    },
    [onChange, value],
  );

  return (
    <>
      <LoadableSelectBox
        options={dashboardOptions}
        value={value || ""}
        placeholder={
          <div className="flex w-full items-center text-light-slate">
            <LayoutDashboard className="mr-2 size-4" />
            <span className="truncate font-normal">Select a dashboard</span>
          </div>
        }
        onChange={handleChangeDashboardId}
        open={isDashboardDropdownOpen}
        onOpenChange={setIsDashboardDropdownOpen}
        buttonSize="sm"
        onLoadMore={
          (dashboardTotal || 0) > DEFAULT_LOADED_DASHBOARDS && !isLoadedMore
            ? loadMoreHandler
            : undefined
        }
        isLoading={isLoadingDashboards}
        optionsCount={DEFAULT_LOADED_DASHBOARDS}
        buttonClassName={cn("w-[300px]", buttonClassName)}
        renderTitle={(option) => {
          return (
            <div className="flex w-full items-center text-foreground">
              <LayoutDashboard className="mr-2 size-4" />
              <span className="max-w-[90%] truncate">{option.label}</span>
            </div>
          );
        }}
        emptyState={<DashboardEmptyState />}
        actionPanel={
          <div className="sticky inset-x-0 bottom-0">
            <Separator className="my-1" />
            <div
              className="flex h-10 cursor-pointer items-center rounded-md px-4 hover:bg-primary-foreground"
              onClick={() => {
                setIsDashboardDropdownOpen(false);
                setIsDashboardDialogOpen(true);
              }}
            >
              <div className="comet-body-s flex items-center gap-2 text-primary">
                <Plus className="size-3.5 shrink-0" />
                <span>Create new dashboard</span>
              </div>
            </div>
          </div>
        }
        disabled={disabled}
        showTooltip
      />
      <AddEditCloneDashboardDialog
        mode="create"
        open={isDashboardDialogOpen}
        setOpen={setIsDashboardDialogOpen}
      />
    </>
  );
};

export default DashboardSelectBox;
