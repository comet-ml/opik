import React, { useMemo } from "react";
import { Check, ChevronsUpDown } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Tag } from "@/components/ui/tag";

import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";

type FeedbackDefinitionSelectorProps = {
  value: string[];
  onChange: (value: string[]) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
};

const FeedbackDefinitionSelector: React.FunctionComponent<
  FeedbackDefinitionSelectorProps
> = ({
  value,
  onChange,
  placeholder = "Select feedback definitions...",
  disabled = false,
  className,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [open, setOpen] = React.useState(false);

  const { data, isPending } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000, // Get all feedback definitions
  });

  const feedbackDefinitions = useMemo(() => data?.content ?? [], [data?.content]);

  const selectedDefinitions = useMemo(() => {
    return feedbackDefinitions.filter((def) => value.includes(def.id));
  }, [feedbackDefinitions, value]);

  const handleSelect = (definitionId: string) => {
    const newValue = value.includes(definitionId)
      ? value.filter((id) => id !== definitionId)
      : [...value, definitionId];
    onChange(newValue);
  };

  const handleRemove = (definitionId: string) => {
    onChange(value.filter((id) => id !== definitionId));
  };

  return (
    <div className={cn("space-y-2", className)}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            role="combobox"
            aria-expanded={open}
            className="w-full justify-between"
            disabled={disabled}
          >
            {value.length > 0
              ? `${value.length} definition${value.length === 1 ? "" : "s"} selected`
              : placeholder}
            <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-full p-0" align="start">
          <Command>
            <CommandInput placeholder="Search feedback definitions..." />
            <CommandList>
              <CommandEmpty>
                {isPending ? "Loading..." : "No feedback definitions found."}
              </CommandEmpty>
              <CommandGroup>
                {feedbackDefinitions.map((definition) => (
                  <CommandItem
                    key={definition.id}
                    value={definition.id}
                    onSelect={() => handleSelect(definition.id)}
                  >
                    <Check
                      className={cn(
                        "mr-2 h-4 w-4",
                        value.includes(definition.id) ? "opacity-100" : "opacity-0"
                      )}
                    />
                    <div className="flex-1">
                      <div className="font-medium">{definition.name}</div>
                      {definition.description && (
                        <div className="text-sm text-muted-foreground">
                          {definition.description}
                        </div>
                      )}
                      <div className="text-xs text-muted-foreground">
                        Type: {definition.type}
                      </div>
                    </div>
                  </CommandItem>
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>

      {selectedDefinitions.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {selectedDefinitions.map((definition) => (
            <Tag
              key={definition.id}
              className="cursor-pointer hover:bg-destructive hover:text-destructive-foreground"
              onClick={() => handleRemove(definition.id)}
            >
              {definition.name}
              <span className="ml-1 text-xs">×</span>
            </Tag>
          ))}
        </div>
      )}
    </div>
  );
};

export default FeedbackDefinitionSelector;
