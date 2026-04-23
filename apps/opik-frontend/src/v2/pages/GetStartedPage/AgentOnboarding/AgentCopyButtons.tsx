import React, { useState } from "react";
import { Check, Clipboard, Key, Layers, LucideIcon } from "lucide-react";
import { Button } from "@/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/ui/tooltip";
import { useActiveWorkspaceName, useUserApiKey } from "@/store/AppStore";
import { useAgentOnboarding } from "./AgentOnboardingContext";

type CopyButtonConfig = {
  key: string;
  value: string;
  icon: LucideIcon;
  iconClassName: string;
  tooltip: string;
};

const AgentCopyButtons: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const apiKey = useUserApiKey();
  const { agentName } = useAgentOnboarding();

  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const handleCopy = (key: string, value: string) => {
    navigator.clipboard.writeText(value);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 1500);
  };

  const buttons: CopyButtonConfig[] = [
    {
      key: "workspace",
      value: workspaceName,
      icon: Layers,
      iconClassName: "text-emerald-600",
      tooltip: "Copy workspace",
    },
    {
      key: "project",
      value: agentName,
      icon: Clipboard,
      iconClassName: "text-pink-500",
      tooltip: "Copy project name",
    },
    ...(apiKey
      ? [
          {
            key: "apiKey",
            value: apiKey,
            icon: Key,
            iconClassName: "text-amber-500",
            tooltip: "Copy the API key",
          },
        ]
      : []),
  ];

  return (
    <TooltipProvider>
      <div className="flex gap-2">
        {buttons.map(({ key, value, icon: Icon, iconClassName, tooltip }) => (
          <div key={key} className="min-w-0 flex-1">
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="xs"
                  className="w-full justify-start overflow-hidden"
                  onClick={() => handleCopy(key, value)}
                >
                  {copiedKey === key ? (
                    <Check className="mr-1.5 size-3.5 shrink-0 text-primary" />
                  ) : (
                    <Icon
                      className={`mr-1.5 size-3.5 shrink-0 ${iconClassName}`}
                    />
                  )}
                  <span className="truncate">{value}</span>
                </Button>
              </TooltipTrigger>
              <TooltipContent>{tooltip}</TooltipContent>
            </Tooltip>
          </div>
        ))}
      </div>
    </TooltipProvider>
  );
};

export default AgentCopyButtons;
