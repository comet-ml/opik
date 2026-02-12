import React from "react";
import { DataTableWrapperProps } from "@/components/shared/DataTable/DataTableWrapper";
import { TABLE_WRAPPER_ATTRIBUTE } from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

const ExperimentsLeaderboardTableWrapper: React.FC<DataTableWrapperProps> = ({
  children,
}) => {
  return (
    <div
      className="comet-sticky-table relative h-full overflow-auto [&_thead[data-sticky-vertical]]:!top-0"
      {...{ [TABLE_WRAPPER_ATTRIBUTE]: "" }}
    >
      {children}
    </div>
  );
};

export default ExperimentsLeaderboardTableWrapper;
