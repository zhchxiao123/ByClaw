const COMMERCIAL_HIDDEN_TABS = new Set(['TENANT', 'SHARED', 'RECOVERY', 'profile', 'health', 'elastic', 'shared']);

export const isSandboxTabVisible = (brandVersion: string | null, tabKey: string): boolean =>
  brandVersion !== 'commercial' || !COMMERCIAL_HIDDEN_TABS.has(tabKey);
