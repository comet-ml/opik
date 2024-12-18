/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";
import { NumericalFeedbackDetailPublic } from "./NumericalFeedbackDetailPublic";

export const NumericalFeedbackDefinitionPublic: core.serialization.ObjectSchema<
    serializers.NumericalFeedbackDefinitionPublic.Raw,
    OpikApi.NumericalFeedbackDefinitionPublic
> = core.serialization.object({
    details: NumericalFeedbackDetailPublic.optional(),
    createdAt: core.serialization.property("created_at", core.serialization.date().optional()),
    createdBy: core.serialization.property("created_by", core.serialization.string().optional()),
    lastUpdatedAt: core.serialization.property("last_updated_at", core.serialization.date().optional()),
    lastUpdatedBy: core.serialization.property("last_updated_by", core.serialization.string().optional()),
});

export declare namespace NumericalFeedbackDefinitionPublic {
    interface Raw {
        details?: NumericalFeedbackDetailPublic.Raw | null;
        created_at?: string | null;
        created_by?: string | null;
        last_updated_at?: string | null;
        last_updated_by?: string | null;
    }
}