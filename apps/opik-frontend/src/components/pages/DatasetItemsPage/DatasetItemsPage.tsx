import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import DatasetItemsPageVersioned from "./DatasetItemsPageVersioned";
import DatasetItemsPageLegacy from "./Legacy/DatasetItemsPage";

const DatasetItemsPage = () => {
  const isVersioningEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_VERSIONING_ENABLED,
  );

  return isVersioningEnabled ? (
    <DatasetItemsPageVersioned />
  ) : (
    <DatasetItemsPageLegacy />
  );
};

export default DatasetItemsPage;
