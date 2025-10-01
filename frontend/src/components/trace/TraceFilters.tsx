import React from "react";

type Props = {
  filters: { hasComment?: boolean };
  setFilters: (f: { hasComment?: boolean }) => void;
};

export default function TraceFilters({ filters, setFilters }: Props) {
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
        Only show traces with comments
      </label>
    </div>
  );
}