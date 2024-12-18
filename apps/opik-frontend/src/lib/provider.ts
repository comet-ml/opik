import { PROVIDER_TYPE, ProviderKey } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";

export const areAllProvidersConfigured = (
  providers: (ProviderKey | PROVIDER_TYPE)[],
) => {
  return providers.length === Object.keys(PROVIDERS).length;
};
