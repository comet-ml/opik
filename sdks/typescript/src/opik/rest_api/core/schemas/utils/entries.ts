export function entries<T extends object>(object: T): [keyof T, T[keyof T]][] {
    return Object.entries(object) as [keyof T, T[keyof T]][];
}
