import React, { useEffect, useRef } from "react";
import { CheckCheck, Plus } from "lucide-react";

import AssertionField from "./AssertionField";

type AssertionsVariant = "item" | "global";

const VARIANT_CONFIG: Record<
  AssertionsVariant,
  { title: string; description: string }
> = {
  item: {
    title: "Assertions",
    description: "Define the conditions for this test to pass",
  },
  global: {
    title: "Global assertions",
    description:
      "Define the global conditions all items in this test suite must pass.",
  },
};

interface AssertionsFieldProps {
  variant: AssertionsVariant;
  headerContent?: React.ReactNode;
  readOnlyAssertions?: string[];
  editableAssertions: string[];
  onChangeEditable: (index: number, value: string) => void;
  onRemoveEditable: (index: number) => void;
  onAdd: () => void;
  placeholder?: string;
}

const AssertionsField: React.FC<AssertionsFieldProps> = ({
  variant,
  headerContent,
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

  const { title, description } = VARIANT_CONFIG[variant];
  const hasReadOnly = readOnlyAssertions.length > 0;
  const hasEditable = editableAssertions.length > 0;

  return (
    <div className="flex flex-col gap-1">
      <span className="comet-body-s-accented">{title}</span>
      <div className="flex items-center justify-between">
        <span className="comet-body-xs text-light-slate">{description}</span>
        <div className="flex shrink-0 items-center gap-3">
          {hasEditable && (
            <button
              type="button"
              className="comet-body-xs-accented inline-flex items-center text-primary"
              onClick={onAdd}
            >
              <Plus className="mr-0.5 size-3" />
              Assertion
            </button>
          )}
          {headerContent}
        </div>
      </div>

      <div className="flex flex-col gap-2 pt-1.5">
        {!hasEditable && (
          <button
            type="button"
            onClick={onAdd}
            className="flex flex-col items-center justify-center gap-1.5 rounded-md border bg-muted/50 p-4"
          >
            <div className="flex size-6 items-center justify-center rounded-full border">
              <CheckCheck className="size-3 text-muted-slate" />
            </div>
            <span className="comet-body-xs text-muted-slate">
              No custom assertions added yet
            </span>
            <span className="comet-body-xs-accented inline-flex items-center text-primary">
              <Plus className="mr-0.5 size-3" />
              Assertion
            </span>
          </button>
        )}

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
      </div>
    </div>
  );
};

export default AssertionsField;
