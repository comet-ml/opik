import React, { useMemo } from "react";
import { Link } from "@tanstack/react-router";
import {
  AlertTriangle,
  MessageSquare,
  Play,
  Cpu,
  Building2,
  Settings,
  Loader2,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Span, Trace, SPAN_TYPE } from "@/types/traces";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import {
  extractPlaygroundData,
  PlaygroundPrefillData,
} from "@/lib/playground/extractPlaygroundData";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

interface OpenInPlaygroundDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
  selectedItem: Trace | Span | undefined;
  treeData: Array<Trace | Span>;
  isPlaygroundEmpty: boolean;
  isLoading?: boolean;
}

const OpenInPlaygroundDialog: React.FC<OpenInPlaygroundDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  selectedItem,
  treeData,
  isPlaygroundEmpty,
  isLoading = false,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Get configured providers
  const { data: providerKeysData } = useProviderKeys({ workspaceName });
  const configuredProviders = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.ui_composed_provider) || [];
  }, [providerKeysData]);

  const { calculateModelProvider } = useLLMProviderModelsData();

  // Extract preview data
  const previewData = useMemo<PlaygroundPrefillData | null>(() => {
    if (!selectedItem) return null;
    return extractPlaygroundData(selectedItem, treeData);
  }, [selectedItem, treeData]);

  // Check if the detected provider is configured
  const providerStatus = useMemo(() => {
    if (!previewData?.provider) {
      return { isConfigured: true, provider: null }; // No provider detected, will use default
    }

    // Check if the provider is in the configured list
    const isConfigured = configuredProviders.some(
      (p) =>
        p.toLowerCase() === previewData.provider?.toLowerCase() ||
        p.toLowerCase().includes(previewData.provider?.toLowerCase() || ""),
    );

    // Also check by model if provider not directly found
    if (!isConfigured && previewData.model) {
      const modelProvider = calculateModelProvider(
        previewData.model as Parameters<typeof calculateModelProvider>[0],
      );
      if (modelProvider && configuredProviders.includes(modelProvider)) {
        return { isConfigured: true, provider: previewData.provider };
      }
    }

    return { isConfigured, provider: previewData.provider };
  }, [previewData, configuredProviders, calculateModelProvider]);

  // Count messages by role
  const messageCounts = useMemo(() => {
    if (!previewData?.messages) return null;

    const counts: Record<string, number> = {};
    previewData.messages.forEach((msg) => {
      const role = msg.role || "unknown";
      counts[role] = (counts[role] || 0) + 1;
    });

    return counts;
  }, [previewData]);

  // Format message counts for display
  const messageCountsDisplay = useMemo(() => {
    if (!messageCounts) return "";

    const parts: string[] = [];
    if (messageCounts[LLM_MESSAGE_ROLE.system]) {
      parts.push(`${messageCounts[LLM_MESSAGE_ROLE.system]} system`);
    }
    if (messageCounts[LLM_MESSAGE_ROLE.user]) {
      parts.push(`${messageCounts[LLM_MESSAGE_ROLE.user]} user`);
    }
    if (messageCounts[LLM_MESSAGE_ROLE.assistant]) {
      parts.push(`${messageCounts[LLM_MESSAGE_ROLE.assistant]} assistant`);
    }
    if (messageCounts[LLM_MESSAGE_ROLE.tool_execution_result]) {
      parts.push(`${messageCounts[LLM_MESSAGE_ROLE.tool_execution_result]} tool`);
    }

    return parts.join(", ");
  }, [messageCounts]);

  // Determine source type
  const isSpan = selectedItem && "trace_id" in selectedItem && "type" in selectedItem;
  const spanType = isSpan ? (selectedItem as Span).type : TRACE_TYPE_FOR_TREE;
  const sourceName = selectedItem?.name || "Unknown";

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Play className="size-5" />
            Open in Playground
          </DialogTitle>
          <DialogDescription>
            Preview what will be loaded into the Playground
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          {/* Source Info */}
          <div className="flex items-start gap-3 rounded-md border bg-muted/30 p-3">
            <div className="mt-0.5">
              <BaseTraceDataTypeIcon
                type={spanType as SPAN_TYPE | typeof TRACE_TYPE_FOR_TREE}
              />
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented">Source</span>
              <span className="comet-body-s text-muted-foreground">
                {isSpan ? "Span" : "Trace"}: {sourceName}
              </span>
            </div>
          </div>

          {/* Messages Info */}
          <div className="flex items-start gap-3 rounded-md border bg-muted/30 p-3">
            <MessageSquare className="mt-0.5 size-5 text-muted-foreground" />
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented">Messages</span>
              <span className="comet-body-s text-muted-foreground">
                {previewData?.messages?.length || 0} messages
                {messageCountsDisplay && ` (${messageCountsDisplay})`}
              </span>
            </div>
          </div>

          {/* Model Info */}
          <div className="flex items-start gap-3 rounded-md border bg-muted/30 p-3">
            <Cpu className="mt-0.5 size-5 text-muted-foreground" />
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented">Model</span>
              <span className="comet-body-s text-muted-foreground">
                {previewData?.model || "Not detected (will use default)"}
              </span>
            </div>
          </div>

          {/* Provider Info */}
          {previewData?.provider && (
            <div className="flex items-start gap-3 rounded-md border bg-muted/30 p-3">
              <Building2 className="mt-0.5 size-5 text-muted-foreground" />
              <div className="flex flex-col gap-0.5">
                <span className="comet-body-s-accented">Provider</span>
                <span className="comet-body-s text-muted-foreground">
                  {previewData.provider}
                </span>
              </div>
            </div>
          )}

          {/* Warning if provider not configured */}
          {!providerStatus.isConfigured && providerStatus.provider && (
            <div className="flex items-start gap-3 rounded-md border border-warning bg-warning-box-bg p-3">
              <Settings className="mt-0.5 size-5 text-warning" />
              <div className="flex flex-col gap-0.5">
                <span className="comet-body-s-accented text-warning">
                  Provider Not Configured
                </span>
                <span className="comet-body-s text-muted-foreground">
                  The provider &quot;{providerStatus.provider}&quot; is not
                  configured. You can still open in Playground, but you&apos;ll
                  need to select a different model or{" "}
                  <Link
                    to="/$workspaceName/configuration"
                    params={{ workspaceName }}
                    search={{ tab: "ai-providers" }}
                    className="text-primary underline hover:no-underline"
                    onClick={() => setOpen(false)}
                  >
                    configure the provider
                  </Link>{" "}
                  first.
                </span>
              </div>
            </div>
          )}

          {/* Warning if playground has content */}
          {!isPlaygroundEmpty && (
            <div className="flex items-start gap-3 rounded-md border border-warning bg-warning-box-bg p-3">
              <AlertTriangle className="mt-0.5 size-5 text-warning" />
              <div className="flex flex-col gap-0.5">
                <span className="comet-body-s-accented text-warning">
                  Warning
                </span>
                <span className="comet-body-s text-muted-foreground">
                  This will replace your current Playground content. Unsaved
                  changes will be lost.
                </span>
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button onClick={onConfirm} disabled={isLoading}>
              {isLoading ? (
                <Loader2 className="mr-1.5 size-3.5 animate-spin" />
              ) : (
                <Play className="mr-1.5 size-3.5" />
              )}
              {isLoading ? "Loading..." : "Open in Playground"}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default OpenInPlaygroundDialog;
