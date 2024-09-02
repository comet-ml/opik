import { create } from "zustand";

type BreadcrumbsStore = {
  params: Record<string, Record<string, string>>;
  setParam: (param: string, key: string, value: string) => void;
};

const useBreadcrumbsStore = create<BreadcrumbsStore>((set) => ({
  params: {},
  setParam: (param, key, value) => {
    set((state) => ({
      ...state,
      params: {
        ...state.params,
        [param]: {
          ...state.params[param],
          [key]: value,
        },
      },
    }));
  },
}));

export default useBreadcrumbsStore;
