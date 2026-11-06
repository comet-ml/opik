import React, { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";

import { TagProps } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { GuardrailPolicyRuleType } from "@/types/guardrails";
import {
  GuardrailRuleTypeIconMap,
  GuardrailRuleTypeLabelMap,
  GuardrailRuleTypeTagVariantMap,
} from "@/constants/guardrails";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ChildrenWidthMeasurer from "@/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";

const OVERFLOW_CONFIG = { itemGap: 4, minFirstItemWidth: 40 };

type RuleTypeTagProps = {
  type: GuardrailPolicyRuleType;
  size: TagProps["size"];
  className?: string;
};

const RuleTypeTag: React.FC<RuleTypeTagProps> = ({ type, size, className }) => (
  <ColoredTag
    label={GuardrailRuleTypeLabelMap[type]}
    variant={GuardrailRuleTypeTagVariantMap[type]}
    IconComponent={GuardrailRuleTypeIconMap[type]}
    size={size}
    className={className}
  />
);

/**
 * Renders a policy's rules as one tag per type, each carrying that type's icon, so a row can be
 * read by shape. The shared list cell already collapses tag overflow but cannot carry an icon.
 */
const RuleTypesCell = (context: CellContext<unknown, unknown>) => {
  const types = (context.getValue() as GuardrailPolicyRuleType[]) ?? [];
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  const { cellRef, visibleItems, hiddenItems, onMeasure } =
    useVisibleItemsByWidth(types, OVERFLOW_CONFIG);

  // Tells "not measured yet" apart from "measured and nothing fits", so the first paint shows the
  // tags rather than collapsing every one of them into the counter.
  const [hasMeasured, setHasMeasured] = useState(false);
  const handleMeasure = useCallback(
    (widths: number[]) => {
      onMeasure(widths);
      setHasMeasured(true);
    },
    [onMeasure],
  );

  if (types.length === 0) {
    return null;
  }

  const typesToRender = hasMeasured ? visibleItems : types;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="relative flex w-full min-w-0 items-center gap-1 overflow-hidden">
        {/* Measured instead of the tag row itself: its width is the container's whatever the
            number of tags rendered, so the count cannot feed back into the measurement. */}
        <div
          ref={cellRef}
          className="pointer-events-none invisible absolute inset-0"
        />
        <ChildrenWidthMeasurer onMeasure={handleMeasure}>
          {types.map((type) => (
            <div key={type}>
              <RuleTypeTag type={type} size={tagSize} className="shrink-0" />
            </div>
          ))}
        </ChildrenWidthMeasurer>
        {typesToRender.map((type) => (
          <RuleTypeTag
            key={type}
            type={type}
            size={tagSize}
            className="block min-w-0 max-w-full"
          />
        ))}
        {hasMeasured && hiddenItems.length > 0 && (
          <TooltipWrapper
            content={
              <div className="flex max-w-[300px] flex-wrap gap-1">
                {types.map((type) => (
                  <RuleTypeTag key={type} type={type} size={tagSize} />
                ))}
              </div>
            }
          >
            <div className="comet-body-xs flex h-5 shrink-0 items-center px-1 text-muted-slate">
              +{hiddenItems.length}
            </div>
          </TooltipWrapper>
        )}
      </div>
    </CellWrapper>
  );
};

export default RuleTypesCell;
