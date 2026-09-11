import useLocalStorageState from "use-local-storage-state";

const STORAGE_KEY = "opik_dashboard_section_collapse_state";

interface SectionCollapseState {
  [sectionId: string]: boolean;
}

export const useSectionCollapseStorage = () => {
  return useLocalStorageState<SectionCollapseState>(STORAGE_KEY, {
    defaultValue: {},
  });
};
