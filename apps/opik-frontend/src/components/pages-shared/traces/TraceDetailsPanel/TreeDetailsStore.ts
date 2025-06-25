import { create } from "zustand";
import isFunction from "lodash/isFunction";
import { OnChangeFn } from "@/types/shared";

type TreeDetailsStore = {
  expandedTreeRows: Set<string>;
  setExpandedTreeRows: OnChangeFn<Set<string>>;
  focusedRowIndex: number;
  setFocusedRowIndex: OnChangeFn<number>;
};

const useTreeDetailsStore = create<TreeDetailsStore>((set) => ({
  expandedTreeRows: new Set(),
  setExpandedTreeRows: (update) => {
    set((state) => ({
      ...state,
      expandedTreeRows: isFunction(update)
        ? update(state.expandedTreeRows)
        : update,
    }));
  },
  focusedRowIndex: 0,
  setFocusedRowIndex: (update) => {
    set((state) => ({
      ...state,
      focusedRowIndex: isFunction(update)
        ? update(state.focusedRowIndex)
        : update,
    }));
  },
}));

export default useTreeDetailsStore;
