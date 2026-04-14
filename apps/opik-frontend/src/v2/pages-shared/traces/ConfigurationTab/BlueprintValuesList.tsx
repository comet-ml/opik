import React, { useEffect, useMemo, useState } from "react";
import { ChevronDown } from "lucide-react";

import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  CustomAccordionTrigger,
} from "@/ui/accordion";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BlueprintTypeIcon from "./BlueprintTypeIcon";
import BlueprintValuePrompt from "./BlueprintValuePrompt";

const renderValue = (v: BlueprintValue) => {
  if (v.type === BlueprintValueType.PROMPT) {
    return <BlueprintValuePrompt key={v.value} value={v} />;
  }

  return (
    <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
      {formatBlueprintValue(v)}
    </div>
  );
};

export const useBlueprintCollapse = (values: BlueprintValue[]) => {
  const allKeys = useMemo(() => values.map((v) => v.key), [values]);
  const [openItems, setOpenItems] = useState<string[]>(allKeys);

  useEffect(() => {
    setOpenItems(allKeys);
  }, [allKeys]);

  const allExpanded = openItems.length === allKeys.length;
  const toggleAll = () => setOpenItems(allExpanded ? [] : allKeys);

  return { openItems, setOpenItems, allExpanded, toggleAll };
};

type BlueprintValuesListProps = {
  values: BlueprintValue[];
  openItems?: string[];
  onOpenItemsChange?: (items: string[]) => void;
};

const BlueprintValuesList: React.FC<BlueprintValuesListProps> = ({
  values,
  openItems,
  onOpenItemsChange,
}) => {
  const allKeys = useMemo(() => values.map((v) => v.key), [values]);
  const [internalOpen, setInternalOpen] = useState<string[]>(allKeys);
  const controlled = openItems !== undefined;

  useEffect(() => {
    if (!controlled) setInternalOpen(allKeys);
  }, [allKeys, controlled]);

  return (
    <Accordion
      type="multiple"
      value={controlled ? openItems : internalOpen}
      onValueChange={controlled ? onOpenItemsChange : setInternalOpen}
    >
      {values.map((v) => (
        <AccordionItem key={v.key} value={v.key} className="border-b py-1">
          <CustomAccordionTrigger className="group/trigger flex items-center gap-2 py-3">
            <ChevronDown className="size-4 shrink-0 -rotate-90 text-light-slate transition-transform duration-200 group-data-[state=open]/trigger:rotate-0" />
            <BlueprintTypeIcon type={v.type} />
            <span className="comet-body-s-accented text-foreground">
              {v.key}
            </span>
          </CustomAccordionTrigger>
          <AccordionContent className="pb-3">
            {v.description && (
              <TooltipWrapper content={v.description}>
                <span className="comet-body-xs mb-2 block w-fit max-w-full truncate text-light-slate">
                  {v.description}
                </span>
              </TooltipWrapper>
            )}
            <div className="overflow-hidden">{renderValue(v)}</div>
          </AccordionContent>
        </AccordionItem>
      ))}
    </Accordion>
  );
};

export default BlueprintValuesList;
