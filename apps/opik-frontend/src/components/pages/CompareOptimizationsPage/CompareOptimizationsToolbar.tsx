import React from "react";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import OptimizationViewSelector, {
  OPTIMIZATION_VIEW_TYPE,
} from "@/components/pages/CompareOptimizationsPage/OptimizationViewSelector";
import { Optimization, OPTIMIZATION_STATUS } from "@/types/optimizations";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationUpdateMutation from "@/api/optimizations/useOptimizationUpdateMutation";

interface CompareOptimizationsToolbarProps {
  isStudioOptimization: boolean;
  optimization: Optimization | undefined;
  view: OPTIMIZATION_VIEW_TYPE;
  onViewChange: (view: OPTIMIZATION_VIEW_TYPE) => void;
  search: string;
  onSearchChange: (search: string) => void;
}

const CompareOptimizationsToolbar: React.FC<
  CompareOptimizationsToolbarProps
> = ({
  isStudioOptimization,
  optimization,
  view,
  onViewChange,
  search,
  onSearchChange,
}) => {
  const showSearch =
    !isStudioOptimization || view === OPTIMIZATION_VIEW_TYPE.TRIALS;

  const updateMutation = useOptimizationUpdateMutation();

  const canCancel =
    isStudioOptimization &&
    optimization &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const handleCancel = () => {
    if (!optimization) return;

    updateMutation.mutate({
      optimizationId: optimization.id,
      update: {
        status: OPTIMIZATION_STATUS.CANCELLED,
      },
    });
  };

  return (
    <>
      {isStudioOptimization && (
        <OptimizationViewSelector value={view} onChange={onViewChange} />
      )}
      {showSearch && (
        <SearchInput
          searchText={search}
          setSearchText={onSearchChange}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        />
      )}
      {canCancel && (
        <Button
          variant="outline"
          size="sm"
          onClick={handleCancel}
          disabled={updateMutation.isPending}
          className="gap-2 border-destructive/50 text-destructive hover:bg-destructive/10 hover:text-destructive"
        >
          <X className="size-4" />
          Cancel Execution
        </Button>
      )}
    </>
  );
};

export default CompareOptimizationsToolbar;
