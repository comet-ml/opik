/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";

export const PromptVersionLinkWrite: core.serialization.ObjectSchema<
    serializers.PromptVersionLinkWrite.Raw,
    OpikApi.PromptVersionLinkWrite
> = core.serialization.object({
    id: core.serialization.string(),
});

export declare namespace PromptVersionLinkWrite {
    export interface Raw {
        id: string;
    }
}
