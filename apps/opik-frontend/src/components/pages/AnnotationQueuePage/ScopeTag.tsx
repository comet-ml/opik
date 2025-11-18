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

interface ScopeTagProps {
  scope: ANNOTATION_QUEUE_SCOPE;
}

const ScopeTag: React.FunctionComponent<ScopeTagProps> = ({ scope }) => {
  return (
    <Tag
      size="md"
      variant="transparent"
      className="flex shrink-0 items-center gap-1"
    >
      {SCOPE_ICON[scope]}
      <div className="comet-body-s-accented truncate text-muted-slate">
        {capitalize(scope)}
      </div>
    </Tag>
  );
};

ScopeTag.displayName = "ScopeTag";

export default ScopeTag;
