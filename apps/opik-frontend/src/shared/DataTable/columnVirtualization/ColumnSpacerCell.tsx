import React from "react";

import { ColumnSpacer } from "@/shared/DataTable/columnVirtualization/columnWindow";

type ColumnSpacerCellProps = {
  spacer: ColumnSpacer;
  isHeader?: boolean;
};

const ColumnSpacerCell: React.FC<ColumnSpacerCellProps> = ({
  spacer,
  isHeader,
}) => {
  const style = { padding: 0, border: 0, width: `${spacer.size}px` };

  return isHeader ? (
    <th aria-hidden style={style} />
  ) : (
    <td aria-hidden style={style} />
  );
};

export default ColumnSpacerCell;
