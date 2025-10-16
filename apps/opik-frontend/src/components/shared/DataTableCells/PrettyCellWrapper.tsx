import React from "react";
import { CellContext } from "@tanstack/react-table";
import PrettyCell from "./PrettyCell";

/**
 * A properly typed wrapper for PrettyCell to avoid using 'as never' type casting
 */
const PrettyCellWrapper = <TData,>(
  context: CellContext<TData, string | object>,
) => {
  return <PrettyCell {...context} />;
};

export default PrettyCellWrapper;
