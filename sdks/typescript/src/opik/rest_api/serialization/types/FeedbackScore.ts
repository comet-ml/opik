/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";
import { FeedbackScoreSource } from "./FeedbackScoreSource";

export const FeedbackScore: core.serialization.ObjectSchema<serializers.FeedbackScore.Raw, OpikApi.FeedbackScore> =
    core.serialization.object({
        name: core.serialization.string(),
        categoryName: core.serialization.property("category_name", core.serialization.string().optional()),
        value: core.serialization.number(),
        reason: core.serialization.string().optional(),
        source: FeedbackScoreSource,
        createdAt: core.serialization.property("created_at", core.serialization.date().optional()),
        lastUpdatedAt: core.serialization.property("last_updated_at", core.serialization.date().optional()),
        createdBy: core.serialization.property("created_by", core.serialization.string().optional()),
        lastUpdatedBy: core.serialization.property("last_updated_by", core.serialization.string().optional()),
    });

export declare namespace FeedbackScore {
    interface Raw {
        name: string;
        category_name?: string | null;
        value: number;
        reason?: string | null;
        source: FeedbackScoreSource.Raw;
        created_at?: string | null;
        last_updated_at?: string | null;
        created_by?: string | null;
        last_updated_by?: string | null;
    }
}