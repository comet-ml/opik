import { CellContext } from "@tanstack/react-table";
import { GitCommitVertical } from "lucide-react";
import { Tag } from "@/ui/tag";

const ConfigurationVersionCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const value = context.getValue() as string;

  if (!value || value === "-") return null;

  return (
    <Tag className="inline-flex items-center gap-1" variant="gray" size="md">
      <GitCommitVertical className="size-3.5 shrink-0" />
      {value}
    </Tag>
  );
};

export default ConfigurationVersionCell;
