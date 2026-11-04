/** Which URLs the ByClaw window may load itself; everything else goes to the system browser. */

/** Origins the main window may navigate to: the web URL's origin plus configured extras. */
export function allowedOrigins(webUrl: string, extraOrigins: readonly string[], loginUrl?: string): Set<string> {
  const origins = new Set([new URL(webUrl).origin])
  for (const extra of loginUrl ? [...extraOrigins, loginUrl] : extraOrigins) {
    try {
      origins.add(new URL(extra).origin)
    }
    catch {
      // Ignore a malformed entry.
    }
  }
  return origins
}

export function isAllowedNavigation(url: string, origins: ReadonlySet<string>): boolean {
  try {
    const parsed = new URL(url)
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && origins.has(parsed.origin)
  }
  catch {
    return false
  }
}

/** Only http(s) and mailto links are handed to the OS; file:, javascript:, custom schemes are dropped. */
export function isExternalOpenable(url: string): boolean {
  try {
    return ['http:', 'https:', 'mailto:'].includes(new URL(url).protocol)
  }
  catch {
    return false
  }
}

/** True when the page URL is on the web origin itself (where the login token may be read). */
export function isWebOrigin(url: string, webUrl: string): boolean {
  try {
    return new URL(url).origin === new URL(webUrl).origin
  }
  catch {
    return false
  }
}
