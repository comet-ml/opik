import { useCallback, useEffect } from "react";
import useLocalStorageState from "use-local-storage-state";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParams,
} from "use-query-params";

import useExperimentsList from "@/api/datasets/useExperimentsList";
import { EVALUATION_METHOD } from "@/types/datasets";
import {
  EXPERIMENTS_TABS,
  ExperimentsTab,
  findExperimentsTab,
  getEvaluationMethodFilter,
} from "./experimentsTabs";

const [TEST_SUITES_TAB, DATASETS_TAB] = EXPERIMENTS_TABS;

const TEST_SUITE_FILTERS = [
  getEvaluationMethodFilter(EVALUATION_METHOD.TEST_SUITE),
];

/**
 * Tab priority: URL ?tab= > last tab opened in this project > Test suites when
 * the project has test-suite experiments > Datasets.
 * Links into the page (sidebar, breadcrumbs) carry no tab, so without the saved
 * tab they would always land on Test suites, and projects with only dataset
 * experiments would open on an empty table.
 */
const useExperimentsTab = (projectId?: string) => {
  // The tabs share the table's URL params, and the URL wins over each tab's
  // saved state (including ?size= over the saved page size), so switching tabs
  // clears them to let the new tab load its own.
  const [{ tab: tabParam }, setQuery] = useQueryParams(
    {
      tab: StringParam,
      search: StringParam,
      filters: JsonParam,
      page: NumberParam,
      size: NumberParam,
      groups: JsonParam,
      limits: JsonParam,
    },
    { updateType: "replaceIn" },
  );

  const [storedTab, setStoredTab] = useLocalStorageState<string>(
    `experiments-tab-${projectId}`,
  );

  const urlTab = findExperimentsTab(tabParam);
  const savedTab = findExperimentsTab(storedTab);
  const needsDefault = !urlTab && !savedTab;

  const {
    data: testSuiteData,
    isPending,
    isError,
  } = useExperimentsList(
    { projectId, filters: TEST_SUITE_FILTERS, page: 1, size: 1 },
    { enabled: !!projectId && needsDefault },
  );

  const defaultTab =
    isError || (testSuiteData?.total ?? 0) > 0 ? TEST_SUITES_TAB : DATASETS_TAB;
  const activeTab: ExperimentsTab = urlTab ?? savedTab ?? defaultTab;

  // A tab opened from a link (e.g. "Back to experiments") is the last one seen.
  useEffect(() => {
    if (urlTab && urlTab.key !== storedTab) setStoredTab(urlTab.key);
  }, [urlTab, storedTab, setStoredTab]);

  const setTab = useCallback(
    (key: string) => {
      setQuery({
        tab: key,
        search: undefined,
        filters: undefined,
        page: undefined,
        size: undefined,
        groups: undefined,
        limits: undefined,
      });
      setStoredTab(key);
    },
    [setQuery, setStoredTab],
  );

  return {
    activeTab,
    setTab,
    isTabPending: needsDefault && !!projectId && isPending && !isError,
  };
};

export default useExperimentsTab;
