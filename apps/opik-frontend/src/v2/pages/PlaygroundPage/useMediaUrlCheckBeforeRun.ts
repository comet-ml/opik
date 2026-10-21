import { useCallback, useRef, useState } from "react";

import { DatasetItem } from "@/types/datasets";
import usePlaygroundStore from "@/store/PlaygroundStore";
import useMediaUrlsCheckMutation, {
  MediaUrlCheckResult,
} from "@/api/playground/useMediaUrlsCheckMutation";
import {
  collectRunMediaUrls,
  getFailedMediaUrls,
} from "@/v2/pages/PlaygroundPage/mediaUrlCheck";

interface UseMediaUrlCheckBeforeRunArguments {
  datasetItems: DatasetItem[];
  runAll: () => unknown;
  runSingle: (promptId: string) => unknown;
}

interface PendingRun {
  failures: MediaUrlCheckResult[];
  run: () => unknown;
}

const useMediaUrlCheckBeforeRun = ({
  datasetItems,
  runAll,
  runSingle,
}: UseMediaUrlCheckBeforeRunArguments) => {
  const { mutateAsync: checkMediaUrls } = useMediaUrlsCheckMutation();
  const [pendingRun, setPendingRun] = useState<PendingRun | null>(null);
  const isCheckingRef = useRef(false);

  const runAfterCheck = useCallback(
    async (promptIds: string[], run: () => unknown) => {
      if (isCheckingRef.current) return;

      const { promptMap } = usePlaygroundStore.getState();
      const prompts = promptIds.map((id) => promptMap[id]).filter(Boolean);
      const urls = collectRunMediaUrls(prompts, datasetItems);
      if (urls.length === 0) {
        run();
        return;
      }

      isCheckingRef.current = true;
      let failures: MediaUrlCheckResult[] = [];
      try {
        failures = getFailedMediaUrls(await checkMediaUrls(urls));
      } catch {
        // our own check failing must never stop a run
        failures = [];
      } finally {
        isCheckingRef.current = false;
      }

      if (failures.length === 0) {
        run();
        return;
      }
      setPendingRun({ failures, run });
    },
    [datasetItems, checkMediaUrls],
  );

  const checkedRunAll = useCallback(
    () => runAfterCheck(usePlaygroundStore.getState().promptIds, runAll),
    [runAfterCheck, runAll],
  );

  const checkedRunSingle = useCallback(
    (promptId: string) => runAfterCheck([promptId], () => runSingle(promptId)),
    [runAfterCheck, runSingle],
  );

  const runPendingRunAnyway = useCallback(() => {
    pendingRun?.run();
    setPendingRun(null);
  }, [pendingRun]);

  const cancelPendingRun = useCallback(() => setPendingRun(null), []);

  return {
    runAll: checkedRunAll,
    runSingle: checkedRunSingle,
    mediaUrlFailures: pendingRun?.failures ?? null,
    runPendingRunAnyway,
    cancelPendingRun,
  };
};

export default useMediaUrlCheckBeforeRun;
