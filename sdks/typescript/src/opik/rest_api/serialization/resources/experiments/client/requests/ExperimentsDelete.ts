/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../../../../index";
import * as OpikApi from "../../../../../api/index";
import * as core from "../../../../../core";

export const ExperimentsDelete: core.serialization.Schema<
    serializers.ExperimentsDelete.Raw,
    OpikApi.ExperimentsDelete
> = core.serialization.object({
    ids: core.serialization.list(core.serialization.string()),
});

export declare namespace ExperimentsDelete {
    interface Raw {
        ids: string[];
    }
}