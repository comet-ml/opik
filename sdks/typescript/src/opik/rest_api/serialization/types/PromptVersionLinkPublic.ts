/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";

export const PromptVersionLinkPublic: core.serialization.ObjectSchema<
    serializers.PromptVersionLinkPublic.Raw,
    OpikApi.PromptVersionLinkPublic
> = core.serialization.object({
    id: core.serialization.string(),
    commit: core.serialization.string().optional(),
    promptId: core.serialization.property("prompt_id", core.serialization.string().optional()),
});

export declare namespace PromptVersionLinkPublic {
    interface Raw {
        id: string;
        commit?: string | null;
        prompt_id?: string | null;
    }
}