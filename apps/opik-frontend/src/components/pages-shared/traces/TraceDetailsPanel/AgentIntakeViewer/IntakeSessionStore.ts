import { create } from "zustand";
import {
  IntakeMessage,
  IntakeConfig,
  INPUT_HINT,
  OptimizationRun,
  OptimizationChange,
  PromptChange,
  AssertionResult,
} from "@/types/agent-intake";
import { CommitResult } from "@/api/agent-intake/useOptimizeStreaming";

export type SelectedEndpoint = {
  id: string;
  name: string;
  url: string;
  secret?: string;
};

export interface OptimizationState {
  isOptimizing: boolean;
  isComplete: boolean;
  success?: boolean;
  cancelled?: boolean;
  runs: OptimizationRun[];
  changes: OptimizationChange[];
  optimizationId?: string;
  promptChanges?: PromptChange[];
  experimentTraces?: Record<string, string>;
  finalAssertionResults?: AssertionResult[];
  commitResult?: CommitResult;
  isCommitting?: boolean;
}

export interface IntakeSessionState {
  messages: IntakeMessage[];
  hasStarted: boolean;
  isReady: boolean;
  config: IntakeConfig | null;
  inputHint: INPUT_HINT;
  optimization: OptimizationState;
  selectedEndpoint: SelectedEndpoint | null;
}

const DEFAULT_OPTIMIZATION_STATE: OptimizationState = {
  isOptimizing: false,
  isComplete: false,
  runs: [],
  changes: [],
};

const DEFAULT_SESSION_STATE: IntakeSessionState = {
  messages: [],
  hasStarted: false,
  isReady: false,
  config: null,
  inputHint: INPUT_HINT.text,
  optimization: DEFAULT_OPTIMIZATION_STATE,
  selectedEndpoint: null,
};

interface IntakeSessionStore {
  sessions: Record<string, IntakeSessionState>;
  getSession: (traceId: string) => IntakeSessionState;
  updateSession: (
    traceId: string,
    updates: Partial<IntakeSessionState>,
  ) => void;
  addMessage: (traceId: string, message: IntakeMessage) => void;
  updateMessage: (
    traceId: string,
    messageId: string,
    updates: Partial<IntakeMessage>,
  ) => void;
  updateOptimization: (
    traceId: string,
    updates: Partial<OptimizationState>,
  ) => void;
  updateOrAddRun: (traceId: string, run: OptimizationRun) => void;
  clearSession: (traceId: string) => void;
}

const useIntakeSessionStore = create<IntakeSessionStore>((set, get) => ({
  sessions: {},

  getSession: (traceId: string) => {
    return get().sessions[traceId] ?? DEFAULT_SESSION_STATE;
  },

  updateSession: (traceId: string, updates: Partial<IntakeSessionState>) => {
    set((state) => ({
      sessions: {
        ...state.sessions,
        [traceId]: {
          ...DEFAULT_SESSION_STATE,
          ...state.sessions[traceId],
          ...updates,
        },
      },
    }));
  },

  addMessage: (traceId: string, message: IntakeMessage) => {
    set((state) => {
      const session = state.sessions[traceId] ?? DEFAULT_SESSION_STATE;
      return {
        sessions: {
          ...state.sessions,
          [traceId]: {
            ...session,
            messages: [...session.messages, message],
          },
        },
      };
    });
  },

  updateMessage: (
    traceId: string,
    messageId: string,
    updates: Partial<IntakeMessage>,
  ) => {
    set((state) => {
      const session = state.sessions[traceId];
      if (!session) return state;
      return {
        sessions: {
          ...state.sessions,
          [traceId]: {
            ...session,
            messages: session.messages.map((m) =>
              m.id === messageId ? { ...m, ...updates } : m,
            ),
          },
        },
      };
    });
  },

  updateOptimization: (traceId: string, updates: Partial<OptimizationState>) => {
    set((state) => {
      const session = state.sessions[traceId] ?? DEFAULT_SESSION_STATE;
      return {
        sessions: {
          ...state.sessions,
          [traceId]: {
            ...session,
            optimization: {
              ...session.optimization,
              ...updates,
            },
          },
        },
      };
    });
  },

  updateOrAddRun: (traceId: string, run: OptimizationRun) => {
    set((state) => {
      const session = state.sessions[traceId] ?? DEFAULT_SESSION_STATE;
      const existingIndex = session.optimization.runs.findIndex(
        (r) => r.iteration === run.iteration,
      );
      const newRuns =
        existingIndex >= 0
          ? session.optimization.runs.map((r, i) =>
              i === existingIndex ? { ...r, ...run } : r,
            )
          : [...session.optimization.runs, run];

      return {
        sessions: {
          ...state.sessions,
          [traceId]: {
            ...session,
            optimization: {
              ...session.optimization,
              runs: newRuns,
            },
          },
        },
      };
    });
  },

  clearSession: (traceId: string) => {
    set((state) => {
      const { [traceId]: _, ...rest } = state.sessions;
      return { sessions: rest };
    });
  },
}));

export default useIntakeSessionStore;
