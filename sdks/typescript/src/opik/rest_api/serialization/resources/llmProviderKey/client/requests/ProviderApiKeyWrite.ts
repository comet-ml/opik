/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../../../../index";
import * as OpikApi from "../../../../../api/index";
import * as core from "../../../../../core";

export const ProviderApiKeyWrite: core.serialization.Schema<
    serializers.ProviderApiKeyWrite.Raw,
    OpikApi.ProviderApiKeyWrite
> = core.serialization.object({
    apiKey: core.serialization.property("api_key", core.serialization.string()),
});

export declare namespace ProviderApiKeyWrite {
    interface Raw {
        api_key: string;
    }
}