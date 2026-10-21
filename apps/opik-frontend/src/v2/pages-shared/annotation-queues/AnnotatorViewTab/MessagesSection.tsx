import React, { useState } from "react";
import { ChevronDown, MoveRight, X } from "lucide-react";
import { Button } from "@/ui/button";
import { cn } from "@/lib/utils";
import { JsonObject } from "@/types/shared";
import { Trace } from "@/types/traces";
import AnnotatorPathInput from "./AnnotatorPathInput";
import { AnnotatorViewDraft } from "./useAnnotatorViewDraft";

type MessageRowProps = {
  label: string;
  sample?: JsonObject;
  isSamplePending: boolean;
  value?: string;
  onChange: (value?: string) => void;
};

const MessageRow: React.FC<MessageRowProps> = ({
  label,
  sample,
  isSamplePending,
  value,
  onChange,
}) => (
  <div className="flex h-6 items-center gap-2">
    <span className="comet-body-s w-28 shrink-0">{label}</span>
    <Button variant="ghost" size="icon-2xs" disabled className="shrink-0">
      <MoveRight />
    </Button>
    <div className="min-w-0 flex-1">
      <AnnotatorPathInput
        path={value}
        treeData={sample ?? {}}
        isPending={isSamplePending}
        placeholder="Automatic"
        onChange={onChange}
      />
    </div>
    <Button
      variant="minimal"
      size="icon-2xs"
      aria-label={`Reset ${label}`}
      className="shrink-0"
      disabled={!value}
      onClick={() => onChange(undefined)}
    >
      <X />
    </Button>
  </div>
);

type MessagesSectionProps = {
  defaultOpen: boolean;
  sample?: Trace;
  isSamplePending: boolean;
  value: AnnotatorViewDraft;
  onChange: (value: AnnotatorViewDraft) => void;
};

const MessagesSection: React.FC<MessagesSectionProps> = ({
  defaultOpen,
  sample,
  isSamplePending,
  value,
  onChange,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  const setMessage = (type: "input" | "output") => (path?: string) =>
    onChange({ ...value, [type]: path });

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <button
        type="button"
        aria-expanded={isOpen}
        onClick={() => setIsOpen(!isOpen)}
        className="flex w-full items-start gap-1.5 p-3 text-left"
      >
        <ChevronDown
          className={cn(
            "mt-0.5 size-3.5 shrink-0 text-light-slate transition-transform",
            !isOpen && "-rotate-90",
          )}
        />
        <div className="flex flex-col gap-0.5">
          <span className="comet-body-s-accented">Messages</span>
          <span className="comet-body-xs text-muted-slate">
            How each trace&apos;s user input and agent output are read, for the
            items list and the thread chat. Empty means automatic.
          </span>
        </div>
      </button>
      {isOpen && (
        <div className="flex flex-col gap-1.5 border-t border-border p-2">
          <MessageRow
            label="User input"
            sample={sample?.input as JsonObject | undefined}
            isSamplePending={isSamplePending}
            value={value.input}
            onChange={setMessage("input")}
          />
          <MessageRow
            label="Agent output"
            sample={sample?.output as JsonObject | undefined}
            isSamplePending={isSamplePending}
            value={value.output}
            onChange={setMessage("output")}
          />
        </div>
      )}
    </div>
  );
};

export default MessagesSection;
