import React from "react";

type Props = {
  filters: { hasComment?: boolean };
  setFilters: (f: { hasComment?: boolean }) => void;
};

export default function ThreadFilters({ filters, setFilters }: Props) {
  return (
    <div className="filters">
      <label>
        <input
          type="checkbox"
          checked={filters.hasComment || false}
          onChange={(e) =>
            setFilters({ ...filters, hasComment: e.target.checked })
          }
        />
        Only show threads with comments
      </label>
    </div>
  );
}