import React, { useState } from "react";
import { Pencil } from "lucide-react";

import { cn } from "@/lib/utils";

type ScoreNameTitleProps = {
  name: string;
  fallbackName?: string;
  hasError?: boolean;
  onChange: (name: string) => void;
};

const ScoreNameTitle: React.FC<ScoreNameTitleProps> = ({
  name,
  fallbackName = "",
  hasError = false,
  onChange,
}) => {
  const [draft, setDraft] = useState<string | null>(null);
  const displayName = name || fallbackName;

  if (draft !== null) {
    const save = () => {
      onChange(draft.trim());
      setDraft(null);
    };
    return (
      <input
        autoFocus
        value={draft}
        placeholder={fallbackName || "Score name"}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={save}
        onKeyDown={(event) => {
          if (event.key === "Enter") save();
          if (event.key === "Escape") {
            event.stopPropagation();
            setDraft(null);
          }
        }}
        aria-label="Score name"
        className="comet-body-xs h-6 min-w-0 flex-1 rounded border border-primary bg-background px-1.5 text-foreground outline-none"
      />
    );
  }

  return (
    <button
      type="button"
      onClick={() => setDraft(displayName)}
      aria-label="Edit score name"
      className="group/score-name comet-body-xs-accented flex min-w-0 flex-1 items-center gap-1 text-left text-muted-slate"
    >
      <span
        className={cn(
          "truncate",
          !displayName && "text-light-slate",
          hasError && "text-destructive",
        )}
      >
        {displayName || "Score name"}
      </span>
      <Pencil className="size-3 shrink-0 opacity-0 transition-opacity group-hover/score-name:opacity-100" />
    </button>
  );
};

export default ScoreNameTitle;
