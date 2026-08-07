// foliate-js 1.0.1 uses ES2024 grouping APIs which are absent in our oldest
// supported WebKit. Keep this small and explicit instead of patching globals in
// the main reader component.
type ObjectGrouping = typeof Object & { groupBy?: (items: Iterable<unknown>, key: (item: unknown, index: number) => PropertyKey) => Record<PropertyKey, unknown[]> };
type MapGrouping = typeof Map & { groupBy?: (items: Iterable<unknown>, key: (item: unknown, index: number) => unknown) => Map<unknown, unknown[]> };

export function installFoliatePolyfills() {
  const objects = Object as ObjectGrouping;
  const maps = Map as MapGrouping;
  if (!objects.groupBy) objects.groupBy = (items, key) => {
    const groups: Record<PropertyKey, unknown[]> = Object.create(null);
    let index = 0;
    for (const item of items) (groups[key(item, index++)] ??= []).push(item);
    return groups;
  };
  if (!maps.groupBy) maps.groupBy = (items, key) => {
    const groups = new Map<unknown, unknown[]>();
    let index = 0;
    for (const item of items) {
      const group = key(item, index++);
      const values = groups.get(group);
      if (values) values.push(item); else groups.set(group, [item]);
    }
    return groups;
  };
}
