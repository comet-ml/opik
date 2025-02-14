import React, { useMemo, useRef } from "react";
import { RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import ChatInput from "@/components/pages/ChatPage/ChatInput";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { useChat, useResetChat } from "@/store/ChatStore";
import ChatMessages from "@/components/pages/ChatPage/ChatMessages";
import { cn } from "@/lib/utils";

const PAGE_FULL_HEIGHT_DIFFERENCE = 140;

const containerStyle = {
  "--page-difference": PAGE_FULL_HEIGHT_DIFFERENCE + "px",
};

const ChatPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const abortControllerRef = useRef<AbortController>();
  const [chatInputHeight, setChatInputHeight] = React.useState(0);

  const chat = useChat();
  const resetChat = useResetChat();
  const noMessages = !chat.messages.length;

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({
      workspaceName,
    });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.provider) || [];
  }, [providerKeysData]);

  return (
    <div className="max-h-full overflow-y-auto pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Chat (Experimental)
        </h1>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            resetChat();
            abortControllerRef.current?.abort();
          }}
        >
          <RotateCcw className="mr-2 size-4" />
          Reset chat
        </Button>
      </div>
      <div
        style={containerStyle as React.CSSProperties}
        className="flex h-[calc(100vh-var(--page-difference))] flex-col"
      >
        <div
          style={
            {
              "--input-height": noMessages ? "70%" : `${chatInputHeight}px`,
            } as React.CSSProperties
          }
          className="h-[calc(100%-var(--input-height))] max-h-full pb-6"
        >
          <ChatMessages messages={chat.messages} />
        </div>
        <div className={cn("flex items-center justify-center")}>
          <ChatInput
            workspaceName={workspaceName}
            providerKeys={providerKeys}
            isPendingProviderKeys={isPendingProviderKeys}
            chat={chat}
            onHeightChange={setChatInputHeight}
            abortControllerRef={abortControllerRef}
          ></ChatInput>
        </div>
      </div>
    </div>
  );
};

export default ChatPage;
