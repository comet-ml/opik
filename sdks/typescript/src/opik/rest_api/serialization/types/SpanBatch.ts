/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";
import { Span } from "./Span";

export const SpanBatch: core.serialization.ObjectSchema<serializers.SpanBatch.Raw, OpikApi.SpanBatch> =
    core.serialization.object({
        spans: core.serialization.list(Span),
    });

export declare namespace SpanBatch {
    interface Raw {
        spans: Span.Raw[];
    }
}