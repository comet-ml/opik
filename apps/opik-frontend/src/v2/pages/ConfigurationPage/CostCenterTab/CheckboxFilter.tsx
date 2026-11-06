import React, { useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import { Input } from "@/ui/input";

type CheckboxFilterProps<T extends string> = {
  label: string;
  options: { value: T; label: string }[];
  selected: T[];
  onChange: (selected: T[]) => void;
  searchable?: boolean;
};

const CheckboxFilter = <T extends string>({
  label,
  options,
  selected,
  onChange,
  searchable = false,
}: CheckboxFilterProps<T>) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const needle = search.trim().toLowerCase();
  const visible = needle
    ? options.filter((option) => option.label.toLowerCase().includes(needle))
    : options;
  const toggle = (value: T) =>
    onChange(
      selected.includes(value)
        ? selected.filter((item) => item !== value)
        : [...selected, value],
    );
  const Chevron = open ? ChevronUp : ChevronDown;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant={selected.length ? "secondary" : "outline"}
          size="sm"
          className="rounded-full"
        >
          {label}
          {selected.length > 0 && ` · ${selected.length}`}
          <Chevron className="ml-1.5 size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-2">
        {searchable && (
          <Input
            dimension="sm"
            className="mb-2"
            placeholder={`Search ${label.toLowerCase()}s`}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        )}
        <div className="max-h-64 overflow-y-auto">
          {visible.map((option) => (
            <label
              key={option.value}
              className="comet-body-s flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 hover:bg-muted"
            >
              <Checkbox
                checked={selected.includes(option.value)}
                onCheckedChange={() => toggle(option.value)}
              />
              {option.label}
            </label>
          ))}
          {!visible.length && (
            <p className="comet-body-s px-2 py-1.5 text-muted-slate">
              No matches
            </p>
          )}
        </div>
        {selected.length > 0 && (
          <Button
            variant="ghost"
            size="xs"
            className="mt-1 w-full"
            onClick={() => onChange([])}
          >
            Clear
          </Button>
        )}
      </PopoverContent>
    </Popover>
  );
};

export default CheckboxFilter;
