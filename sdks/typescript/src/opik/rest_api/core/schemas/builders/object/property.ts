import { Schema } from "../../Schema";

export function property<RawKey extends string, RawValue, ParsedValue>(
    rawKey: RawKey,
    valueSchema: Schema<RawValue, ParsedValue>,
): Property<RawKey, RawValue, ParsedValue> {
    return {
        rawKey,
        valueSchema,
        isProperty: true,
    };
}

export interface Property<RawKey extends string, RawValue, ParsedValue> {
    rawKey: RawKey;
    valueSchema: Schema<RawValue, ParsedValue>;
    isProperty: true;
}

export function isProperty<O extends Property<any, any, any>>(maybeProperty: unknown): maybeProperty is O {
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
    return (maybeProperty as O).isProperty;
}
