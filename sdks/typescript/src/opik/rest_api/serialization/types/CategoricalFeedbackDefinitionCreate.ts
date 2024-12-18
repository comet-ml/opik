/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";
import { CategoricalFeedbackDetailCreate } from "./CategoricalFeedbackDetailCreate";

export const CategoricalFeedbackDefinitionCreate: core.serialization.ObjectSchema<
    serializers.CategoricalFeedbackDefinitionCreate.Raw,
    OpikApi.CategoricalFeedbackDefinitionCreate
> = core.serialization.object({
    details: CategoricalFeedbackDetailCreate.optional(),
});

export declare namespace CategoricalFeedbackDefinitionCreate {
    interface Raw {
        details?: CategoricalFeedbackDetailCreate.Raw | null;
    }
}