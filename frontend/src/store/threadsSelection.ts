import { create } from "zustand";

type State = {
  selected: Set<string>;
  select: (id: string, selected: boolean) => void;
  clear: () => void;
  getSelectedArray: () => string[];
};

export const useThreadsSelection = create<State>((set, get) => ({
  selected: new Set<string>(),
  select: (id, isSelected) =>
    set((state) => {
      const next = new Set(state.selected);
      if (isSelected) next.add(id);
      else next.delete(id);
      return { selected: next };
    }),
  clear: () => set({ selected: new Set<string>() }),
  getSelectedArray: () => Array.from(get().selected),
}));