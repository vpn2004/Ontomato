export function initialNodePosition(index: number, count: number) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(count)));
  return {
    x: (index % columns) * 180,
    y: Math.floor(index / columns) * 140,
  };
}

function seededRandom(seed = 20260915) {
  let value = seed >>> 0;
  return () => {
    value += 0x6d2b79f5;
    let next = value;
    next = Math.imul(next ^ (next >>> 15), next | 1);
    next ^= next + Math.imul(next ^ (next >>> 7), next | 61);
    return ((next ^ (next >>> 14)) >>> 0) / 4294967296;
  };
}

export function graphLayout() {
  return {
    type: "d3-force" as const,
    preventOverlap: true,
    linkDistance: 180,
    nodeStrength: -420,
    randomSource: seededRandom(),
  };
}
