import React from "react";
import { SearchInput } from "@/shared/SearchInput/SearchInput";
import SpendPeriodSelect from "@/v2/pages-shared/SpendPeriodSelect/SpendPeriodSelect";
import { SpendWindow } from "@/lib/aiSpend";
import LeaderboardSortSelect, {
  LeaderboardSortKey,
} from "./LeaderboardSortSelect";

interface LeaderboardToolbarProps {
  search: string;
  onSearchChange: (value: string) => void;
  sort: LeaderboardSortKey;
  onSortChange: (value: LeaderboardSortKey) => void;
  windowDays: SpendWindow;
  onWindowChange: (value: SpendWindow) => void;
}

const LeaderboardToolbar: React.FC<LeaderboardToolbarProps> = ({
  search,
  onSearchChange,
  sort,
  onSortChange,
  windowDays,
  onWindowChange,
}) => (
  <div className="mb-4 flex items-center justify-between gap-3">
    <SearchInput
      searchText={search}
      setSearchText={onSearchChange}
      placeholder="Search users"
      dimension="sm"
      className="w-[260px]"
    />
    <div className="flex items-center gap-3">
      <LeaderboardSortSelect value={sort} onChange={onSortChange} />
      <SpendPeriodSelect value={windowDays} onChange={onWindowChange} />
    </div>
  </div>
);

export default LeaderboardToolbar;
