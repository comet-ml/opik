/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";
import { ColumnPublicTypesItem } from "./ColumnPublicTypesItem";

export const ColumnPublic: core.serialization.ObjectSchema<serializers.ColumnPublic.Raw, OpikApi.ColumnPublic> =
    core.serialization.object({
        name: core.serialization.string().optional(),
        types: core.serialization.list(ColumnPublicTypesItem).optional(),
    });

export declare namespace ColumnPublic {
    interface Raw {
        name?: string | null;
        types?: ColumnPublicTypesItem.Raw[] | null;
    }
}