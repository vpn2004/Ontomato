/** Only new committed results renew the idle deadline; a finite total budget always applies. */
export function createProgressBudget(idleMs: number, totalMs: number, onExpire: () => void) {
  const seen = new Set<string>();
  let closed = false;
  const expire = () => { if (!closed) { dispose(); onExpire(); } };
  let idle = setTimeout(expire, idleMs);
  const total = setTimeout(expire, Math.max(idleMs, totalMs));
  function dispose() { closed = true; clearTimeout(idle); clearTimeout(total); }
  return {
    progress(key: string) {
      if (closed || !key || seen.has(key)) return;
      seen.add(key);
      clearTimeout(idle);
      idle = setTimeout(expire, idleMs);
    },
    dispose,
  };
}
