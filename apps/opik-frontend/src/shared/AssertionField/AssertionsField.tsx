import React, { useEffect, useRef } from "react";
import { CheckCheck, Plus } from "lucide-react";

import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import AssertionField from "./AssertionField";

interface AssertionsFieldProps {
  readOnlyAssertions?: string[];
  editableAssertions: string[];
  onChangeEditable: (index: number, value: string) => void;
  onRemoveEditable: (index: number) => void;
  onAdd: () => void;
  placeholder?: string;
}

const AssertionsField: React.FC<AssertionsFieldProps> = ({
  readOnlyAssertions = [],
  editableAssertions,
  onChangeEditable,
  onRemoveEditable,
  onAdd,
  placeholder = "e.g. Response should be factually accurate",
}) => {
  const prevCountRef = useRef(editableAssertions.length);
  const shouldAutoFocusLast = editableAssertions.length > prevCountRef.current;

  useEffect(() => {
    prevCountRef.current = editableAssertions.length;
  }, [editableAssertions.length]);

  const hasReadOnly = readOnlyAssertions.length > 0;
  const hasEditable = editableAssertions.length > 0;
  const hasAny = hasReadOnly || hasEditable;

  return (
    <div className="flex flex-col gap-2 pt-1.5">
      {!hasAny && (
        <div className="flex h-[80px] flex-col items-center justify-center rounded-md border px-4">
          <div className="flex size-4 items-center justify-center rounded bg-[#89DEFF]">
            <CheckCheck className="size-2 text-foreground" />
          </div>
          <span className="comet-body-xs mt-2 text-muted-slate">
            No assertions added yet
          </span>
        </div>
      )}

      {hasReadOnly && (
        <div className="flex flex-col gap-2">
          {readOnlyAssertions.map((assertion, index) => (
            <AssertionField
              key={`global-${index}`}
              isReadOnly
              value={assertion}
            />
          ))}
        </div>
      )}

      {hasReadOnly && hasEditable && <Separator className="my-1" />}

      {hasEditable && (
        <div className="flex flex-col gap-2">
          {editableAssertions.map((assertion, index) => (
            <AssertionField
              key={index}
              autoFocus={
                shouldAutoFocusLast && index === editableAssertions.length - 1
              }
              placeholder={placeholder}
              value={assertion}
              onChange={(e) => onChangeEditable(index, e.target.value)}
              onRemove={() => onRemoveEditable(index)}
            />
          ))}
        </div>
      )}

      <Button
        type="button"
        variant="ghost"
        size="2xs"
        className="w-fit"
        onClick={onAdd}
      >
        <Plus className="mr-0.5 size-3" />
        Assertion
      </Button>
    </div>
  );
};

export default AssertionsField;
