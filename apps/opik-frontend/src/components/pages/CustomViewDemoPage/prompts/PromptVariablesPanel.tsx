/**
 * Panel showing available template variables with click-to-insert functionality
 */

import React from "react";
import { PromptVariablesPanelProps } from "./types";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const PromptVariablesPanel: React.FC<PromptVariablesPanelProps> = ({
  variables,
  onInsertVariable,
}) => {
  return (
    <div className="rounded-md border border-border bg-muted/30 p-3">
      <h4 className="comet-body-s-accented mb-2 text-muted-slate">
        Available Variables
      </h4>
      <p className="comet-body-xs mb-3 text-light-slate">
        Click a variable to insert it at the cursor position
      </p>
      <div className="flex flex-col gap-2">
        {variables.map((variable) => (
          <div key={variable.name} className="flex items-start gap-2">
            <Button
              variant="outline"
              size="sm"
              className={cn(
                "h-auto shrink-0 px-2 py-1 font-mono text-xs",
                "text-[var(--color-green)] hover:bg-[var(--color-green)]/10",
              )}
              onClick={() => onInsertVariable(variable.name)}
            >
              {`{{${variable.name}}}`}
            </Button>
            <span className="comet-body-xs pt-1 text-muted-slate">
              {variable.description}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
};

export default PromptVariablesPanel;
