import { CellContext } from "@tanstack/react-table";
import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import ConfigurationVersionTag from "@/shared/ConfigurationVersionTag/ConfigurationVersionTag";

export type ConfigurationVersionCellData = {
  version: string;
  maskId?: string;
};

const ConfigurationVersionCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const value = context.getValue() as ConfigurationVersionCellData | undefined;
  const isSmall =
    (context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small) ===
    ROW_HEIGHT.small;

  if (!value) return null;

  return (
    <div
      className={cn("flex h-full items-start px-2", isSmall ? "py-1" : "pt-2")}
    >
      <ConfigurationVersionTag
        version={value.version}
        maskId={value.maskId}
        variant="white"
      />
    </div>
  );
};

export default ConfigurationVersionCell;
