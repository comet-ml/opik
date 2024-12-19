import { PROVIDER_TYPE, ProviderKey } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import first from "lodash/first";

export const areAllProvidersConfigured = (
  providers: (ProviderKey | PROVIDER_TYPE)[],
) => {
  return providers.length === Object.keys(PROVIDERS).length;
};

export const getDefaultProviderKey = (providers: PROVIDER_TYPE[]) => {
  return first(providers) || "";
};
