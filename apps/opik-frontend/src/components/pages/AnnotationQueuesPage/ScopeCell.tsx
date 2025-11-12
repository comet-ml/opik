import { CellContext } from "@tanstack/react-table";
import { ListTree, MessagesSquare } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import capitalize from "lodash/capitalize";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";

const SCOPE_ICON = {
  [ANNOTATION_QUEUE_SCOPE.TRACE]: (
    <ListTree className="size-3 shrink-0 text-[var(--color-green)]" />
  ),
  [ANNOTATION_QUEUE_SCOPE.THREAD]: (
    <MessagesSquare className="size-3 shrink-0 text-[var(--thread-icon-text)]" />
  ),
};

const ScopeCell = (context: CellContext<unknown, string>) => {
  const scope = context.getValue().toLowerCase() as ANNOTATION_QUEUE_SCOPE;

  return (
    <Tag
      size="md"
      variant="transparent"
      className="flex w-fit shrink-0 items-center gap-1"
    >
      {SCOPE_ICON[scope]}
      <div className="truncate">{capitalize(scope)}</div>
    </Tag>
  );
};

ScopeCell.displayName = "ScopeCell";

export default ScopeCell;
