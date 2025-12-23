import React from "react";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import OptimizationViewSelector, {
  OPTIMIZATION_VIEW_TYPE,
} from "@/components/pages/CompareOptimizationsPage/OptimizationViewSelector";

interface CompareOptimizationsToolbarProps {
  isStudioOptimization: boolean;
  view: OPTIMIZATION_VIEW_TYPE;
  onViewChange: (view: OPTIMIZATION_VIEW_TYPE) => void;
  search: string;
  onSearchChange: (search: string) => void;
}

const CompareOptimizationsToolbar: React.FC<
  CompareOptimizationsToolbarProps
> = ({ isStudioOptimization, view, onViewChange, search, onSearchChange }) => {
  const showSearch =
    !isStudioOptimization || view === OPTIMIZATION_VIEW_TYPE.TRIALS;

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
    </>
  );
};

export default CompareOptimizationsToolbar;
