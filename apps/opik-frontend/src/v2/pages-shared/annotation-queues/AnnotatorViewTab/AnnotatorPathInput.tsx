import React from "react";
import { ChevronDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import PathSourcePicker from "@/v2/pages-shared/traces/AddToDatasetDialog/PathSourcePicker";
import { JsonObject } from "@/types/shared";
import { cn, setJSONPathIndex, splitJSONPathByIndexes } from "@/lib/utils";
import { toAnnotatorPath } from "./annotatorViewConfig";

const FIRST_INDEX = 0;
const LAST_INDEX = -1;

const indexLabel = (index: number) => {
  if (index === FIRST_INDEX) return "first";
  if (index === LAST_INDEX) return "last";
  return String(index);
};

const stopPropagation = (event: React.SyntheticEvent) =>
  event.stopPropagation();

type IndexChipProps = {
  index: number;
  onChange: (index: number) => void;
};

const IndexChip: React.FC<IndexChipProps> = ({ index, onChange }) => {
  const options = [
    { value: FIRST_INDEX, label: "First" },
    { value: LAST_INDEX, label: "Last" },
    ...(index === FIRST_INDEX || index === LAST_INDEX
      ? []
      : [{ value: index, label: `Index ${index}` }]),
  ];

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="mx-0.5 inline-flex h-4 shrink-0 items-center gap-0.5 rounded bg-primary-50 px-1 text-[11px] text-primary hover:bg-primary-100"
          onClick={stopPropagation}
          onKeyDown={stopPropagation}
        >
          {indexLabel(index)}
          <ChevronDown className="size-2.5" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" onClick={stopPropagation}>
        {options.map((option) => (
          <DropdownMenuCheckboxItem
            key={option.value}
            checked={option.value === index}
            onSelect={() => onChange(option.value)}
          >
            {option.label}
          </DropdownMenuCheckboxItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

type AnnotatorPathInputProps = {
  path?: string;
  treeData: JsonObject;
  isPending?: boolean;
  invalid?: boolean;
  placeholder?: string;
  onChange: (path: string) => void;
};

const AnnotatorPathInput: React.FC<AnnotatorPathInputProps> = ({
  path,
  treeData,
  isPending,
  invalid,
  placeholder = "Select a field",
  onChange,
}) => {
  const trigger = (
    <div
      role="button"
      tabIndex={0}
      data-testid="annotator-path-input"
      className={cn(
        "flex h-6 w-full min-w-0 cursor-pointer items-center gap-1 rounded-md border border-border bg-background px-2 hover:border-slate-300",
        invalid && "border-destructive",
      )}
    >
      <span
        className={cn(
          "comet-code flex min-w-0 flex-1 items-center overflow-hidden whitespace-nowrap text-xs",
          path ? "text-foreground" : "text-light-slate",
        )}
      >
        {path
          ? splitJSONPathByIndexes(path).map((part, partIndex) =>
              part.kind === "text" ? (
                <span key={partIndex} className="truncate">
                  {part.text}
                </span>
              ) : (
                <IndexChip
                  key={partIndex}
                  index={part.index}
                  onChange={(index) =>
                    onChange(setJSONPathIndex(path, part.position, index))
                  }
                />
              ),
            )
          : placeholder}
      </span>
      <ChevronDown className="size-3 shrink-0 text-muted-slate" />
    </div>
  );

  return (
    <PathSourcePicker
      path={path}
      treeData={treeData}
      isPending={isPending}
      trigger={trigger}
      onSelect={(selectedPath) =>
        onChange(toAnnotatorPath(treeData, selectedPath))
      }
    />
  );
};

export default AnnotatorPathInput;
