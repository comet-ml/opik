import { useCallback } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParams,
} from "use-query-params";

import { DatasetVersion } from "@/types/datasets";
import { DVS_QUERY_PREFIX } from "@/v2/pages-shared/datasets/DatasetItemsView/constants";

const VERSION_KEY = `${DVS_QUERY_PREFIX}version`;

const DVS_PARAMS = {
  [VERSION_KEY]: StringParam,
  [`${DVS_QUERY_PREFIX}row`]: StringParam,
  [`${DVS_QUERY_PREFIX}page`]: NumberParam,
  [`${DVS_QUERY_PREFIX}search`]: StringParam,
  [`${DVS_QUERY_PREFIX}filters`]: JsonParam,
  [`${DVS_QUERY_PREFIX}size`]: NumberParam,
  [`${DVS_QUERY_PREFIX}height`]: StringParam,
};

const CLEARED_PARAMS = Object.fromEntries(
  Object.keys(DVS_PARAMS).map((key) => [key, undefined]),
);

export const useVersionRecordsSidebarControls = () => {
  const [params, setParams] = useQueryParams(DVS_PARAMS);

  const openVersion = useCallback(
    (version: DatasetVersion) =>
      setParams({ [VERSION_KEY]: version.version_hash }, "replaceIn"),
    [setParams],
  );

  const closeVersion = useCallback(
    () => setParams(CLEARED_PARAMS, "replaceIn"),
    [setParams],
  );

  return {
    versionHash: params[VERSION_KEY] ?? undefined,
    openVersion,
    closeVersion,
  };
};
