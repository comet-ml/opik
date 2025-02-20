import { create } from "zustand";
import { persist } from "zustand/middleware";
import cloneDeep from "lodash/cloneDeep";
import { ChatLLMessage, LLMChatType } from "@/types/llm";

export type ChatStore = {
  chat: LLMChatType;
  scrollKey: number;
  updateChat: (changes: Partial<LLMChatType>) => void;
  resetChat: () => void;
  updateMessage: (messageId: string, changes: Partial<ChatLLMessage>) => void;
  deleteMessage: (messageId: string) => void;
};

const EMPTY_CHAT: LLMChatType = {
  value: "",
  messages: [],
  model: "",
  provider: "",
  configs: {},
};

const useChatStore = create<ChatStore>()(
  persist(
    (set) => ({
      chat: cloneDeep(EMPTY_CHAT),
      scrollKey: 0,
      updateChat: (changes) => {
        set((state) => {
          const newState = {
            ...state.chat,
            ...changes,
          };

          return {
            ...state,
            chat: newState,
          };
        });
      },
      resetChat: () => {
        set((state) => {
          return {
            ...state,
            chat: {
              ...cloneDeep(EMPTY_CHAT),
              provider: state.chat.provider,
              model: state.chat.model,
              configs: state.chat.configs,
            },
          };
        });
      },
      updateMessage: (messageId, changes: Partial<ChatLLMessage>) => {
        set((state) => {
          let hasChanges = false;
          const messages = state.chat.messages.map((m) => {
            if (m.id === messageId) {
              hasChanges = true;
              return {
                ...m,
                ...changes,
              };
            }
            return m;
          });

          if (hasChanges) {
            return {
              ...state,
              chat: {
                ...state.chat,
                messages,
              },
              scrollKey: changes.isLoading ? 0 : state.scrollKey + 1,
            };
          }

          return state;
        });
      },
      deleteMessage: (messageId) => {
        set((state) => {
          return {
            ...state,
            chat: {
              ...state.chat,
              messages: state.chat.messages.filter((m) => m.id !== messageId),
            },
            scrollKey: state.scrollKey + 1,
          };
        });
      },
    }),
    {
      name: "CHAT_STATE",
    },
  ),
);

export const useChat = () => useChatStore((state) => state.chat);
export const useScrollKey = () => useChatStore((state) => state.scrollKey);

export const useUpdateChat = () => useChatStore((state) => state.updateChat);
export const useResetChat = () => useChatStore((state) => state.resetChat);
export const useUpdateMessage = () =>
  useChatStore((state) => state.updateMessage);
export const useDeleteMessage = () =>
  useChatStore((state) => state.deleteMessage);

export default useChatStore;
