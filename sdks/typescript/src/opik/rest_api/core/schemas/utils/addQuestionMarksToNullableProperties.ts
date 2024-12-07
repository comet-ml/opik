export type addQuestionMarksToNullableProperties<T> = {
    [K in OptionalKeys<T>]?: T[K];
} & Pick<T, RequiredKeys<T>>;

export type OptionalKeys<T> = {
    [K in keyof T]-?: undefined extends T[K]
        ? K
        : null extends T[K]
        ? K
        : 1 extends (any extends T[K] ? 0 : 1)
        ? never
        : K;
}[keyof T];

export type RequiredKeys<T> = Exclude<keyof T, OptionalKeys<T>>;
