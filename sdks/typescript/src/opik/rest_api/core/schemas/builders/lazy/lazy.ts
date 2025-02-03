import { BaseSchema, Schema } from "../../Schema";
import { getSchemaUtils } from "../schema-utils";

export type SchemaGetter<SchemaType extends Schema<any, any>> = () => SchemaType;

export function lazy<Raw, Parsed>(getter: SchemaGetter<Schema<Raw, Parsed>>): Schema<Raw, Parsed> {
    const baseSchema = constructLazyBaseSchema(getter);
    return {
        ...baseSchema,
        ...getSchemaUtils(baseSchema),
    };
}

export function constructLazyBaseSchema<Raw, Parsed>(
    getter: SchemaGetter<Schema<Raw, Parsed>>,
): BaseSchema<Raw, Parsed> {
    return {
        parse: (raw, opts) => getMemoizedSchema(getter).parse(raw, opts),
        json: (parsed, opts) => getMemoizedSchema(getter).json(parsed, opts),
        getType: () => getMemoizedSchema(getter).getType(),
    };
}

type MemoizedGetter<SchemaType extends Schema<any, any>> = SchemaGetter<SchemaType> & { __zurg_memoized?: SchemaType };

export function getMemoizedSchema<SchemaType extends Schema<any, any>>(getter: SchemaGetter<SchemaType>): SchemaType {
    const castedGetter = getter as MemoizedGetter<SchemaType>;
    if (castedGetter.__zurg_memoized == null) {
        castedGetter.__zurg_memoized = getter();
    }
    return castedGetter.__zurg_memoized;
}
