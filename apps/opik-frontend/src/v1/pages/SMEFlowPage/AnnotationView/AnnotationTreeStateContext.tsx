import React, {
  createContext,
  useContext,
  useState,
  useCallback,
  ReactNode,
} from "react";

/**
 * State for a single section (input/output) in the annotation viewer
 * to key-based paths internally for robustness across different tree structures
 */
interface SectionState {
  scrollTop: number;
}

/**
 * Complete tree state for all sections in the annotation viewer
 * Note: metadata is excluded because it doesn't have prettifyConfig and only displays
 * as flat text (YAML/JSON) via CodeMirrorHighlighter, never as an expandable tree
 */
interface AnnotationTreeState {
  input: SectionState;
  output: SectionState;
}

interface AnnotationTreeStateContextValue {
  state: AnnotationTreeState;
  updateScrollTop: (
    section: keyof AnnotationTreeState,
    scrollTop: number,
  ) => void;
  getScrollTop: (section: keyof AnnotationTreeState) => number;
}

const AnnotationTreeStateContext = createContext<
  AnnotationTreeStateContextValue | undefined
>(undefined);

const createDefaultSectionState = (): SectionState => ({
  scrollTop: 0,
});

const createDefaultState = (): AnnotationTreeState => ({
  input: createDefaultSectionState(),
  output: createDefaultSectionState(),
});

interface AnnotationTreeStateProviderProps {
  children: ReactNode;
}

/**
 * Provider that maintains tree expansion state and scroll positions across
 * trace navigation in annotation queues.
 *
 * This allows users to expand/collapse nodes and scroll to specific positions,
 * and those states persist when navigating to the next/previous trace.
 */
export const AnnotationTreeStateProvider: React.FC<
  AnnotationTreeStateProviderProps
> = ({ children }) => {
  const [state, setState] = useState<AnnotationTreeState>(createDefaultState);

  const updateScrollTop = useCallback(
    (section: keyof AnnotationTreeState, scrollTop: number) => {
      setState((prev) => ({
        ...prev,
        [section]: {
          ...prev[section],
          scrollTop,
        },
      }));
    },
    [],
  );

  const getScrollTop = useCallback(
    (section: keyof AnnotationTreeState) => {
      return state[section].scrollTop;
    },
    [state],
  );

  const value: AnnotationTreeStateContextValue = {
    state,
    updateScrollTop,
    getScrollTop,
  };

  return (
    <AnnotationTreeStateContext.Provider value={value}>
      {children}
    </AnnotationTreeStateContext.Provider>
  );
};

/**
 * Hook to access annotation tree state context
 */
export const useAnnotationTreeState = () => {
  const context = useContext(AnnotationTreeStateContext);
  if (!context) {
    throw new Error(
      "useAnnotationTreeState must be used within AnnotationTreeStateProvider",
    );
  }
  return context;
};
