import React from "react";
import { X } from "lucide-react";

import { Tag } from "@/ui/tag";
import ChildrenWidthMeasurer from "@/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";

const CHIPS_CONFIG = { itemGap: 4 };

type FeedbackDefinitionChipsProps = {
  names: string[];
  onRemove: (name: string) => void;
};

/**
 * The selected feedback scores, shown as removable chips inside the closed select.
 *
 * Only the chips that actually fit are rendered, with a "+N" for the rest - the same measured approach
 * the tag columns use, so a queue with a dozen scores does not stretch the field.
 *
 * The remove control is a span rather than a button on purpose: this renders inside the select's
 * trigger button, and a nested button is invalid. That constraint is on the element, not on
 * accessibility - it carries tabIndex and its own Enter/Space handling so keyboard users can remove a
 * chip, which role="button" alone does not provide. Pointer events are stopped so removing a chip does
 * not also open the dropdown.
 */
const FeedbackDefinitionChips: React.FC<FeedbackDefinitionChipsProps> = ({
  names,
  onRemove,
}) => {
  const { cellRef, visibleItems, hiddenItems, onMeasure } =
    useVisibleItemsByWidth(names, CHIPS_CONFIG);

  return (
    <div ref={cellRef} className="w-full min-w-0 overflow-hidden">
      <div className="flex flex-row gap-1 overflow-x-hidden">
        <ChildrenWidthMeasurer onMeasure={onMeasure}>
          {names.map((name) => (
            <div key={name}>
              <Tag size="sm" variant="gray" className="shrink-0">
                {name}
              </Tag>
            </div>
          ))}
        </ChildrenWidthMeasurer>
        {visibleItems.map((name) => (
          <Tag
            key={name}
            size="sm"
            variant="gray"
            className="flex min-w-0 max-w-full items-center gap-1"
          >
            <span className="truncate">{name}</span>
            <span
              role="button"
              tabIndex={0}
              aria-label={`Remove ${name}`}
              className="shrink-0 cursor-pointer rounded text-light-slate hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-ring"
              onPointerDown={(event) => event.stopPropagation()}
              onClick={(event) => {
                event.stopPropagation();
                onRemove(name);
              }}
              // A span with role="button" is not focusable or activatable on its own, so both are
              // supplied here. Enter and Space are stopped as well as handled, or Space would scroll
              // the dropdown and Enter would submit the surrounding form.
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  event.stopPropagation();
                  onRemove(name);
                }
              }}
            >
              <X className="size-3" />
            </span>
          </Tag>
        ))}
        {hiddenItems.length > 0 && (
          <Tag size="sm" variant="gray" className="shrink-0">
            +{hiddenItems.length}
          </Tag>
        )}
      </div>
    </div>
  );
};

export default FeedbackDefinitionChips;
