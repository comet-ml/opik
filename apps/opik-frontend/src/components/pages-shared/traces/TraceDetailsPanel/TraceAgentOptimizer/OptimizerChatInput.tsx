import React from "react";
import { StopCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { OptimizerChatType } from "@/types/agent-optimizer";

interface OptimizerChatInputProps {
  chat: OptimizerChatType;
  isRunning: boolean;
  onUpdateChat: (changes: Partial<OptimizerChatType>) => void;
  onStop: () => void;
}

const OptimizerChatInput: React.FC<OptimizerChatInputProps> = ({
  isRunning,
  onStop,
}) => {
  if (isRunning) {
    return (
      <div className="flex items-center justify-center gap-2 py-2">
        <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        <span className="text-sm text-muted-foreground">Processing...</span>
        <Button variant="outline" size="sm" onClick={onStop}>
          <StopCircle className="mr-2 h-4 w-4" />
          Stop
        </Button>
      </div>
    );
  }

  return (
    <div className="text-center text-sm text-muted-foreground py-2">
      Respond to prompts above to continue the optimization process.
    </div>
  );
};

export default OptimizerChatInput;
