/**
 * Pairing from the logged-in page: read the web login token (byclaw-fe keeps it in localStorage
 * `Beyond-Token`) only when the page is on the configured web origin, call the BE pair API once,
 * and hand the device token back to main. The page never sees the device token.
 */

import type { WebContents } from 'electron'
import { pairDevice, type PairResult } from '@byclaw/node-runtime'
import { isWebOrigin } from './navigation.js'

/** localStorage key byclaw-fe stores the login JWT under (byclaw-fe/src/utils/auth.ts `tokenKey`). */
export const LOGIN_TOKEN_KEY = 'Beyond-Token'

/** An isolated world id for our reads: page scripts cannot patch `localStorage.getItem` there. */
const ISOLATED_WORLD = 7311

/** The page's login token, or undefined when not logged in or not on the web origin. */
export async function readLoginToken(contents: WebContents, webUrl: string): Promise<string | undefined> {
  if (contents.isDestroyed() || !isWebOrigin(contents.getURL(), webUrl)) return undefined
  try {
    const value: unknown = await contents.executeJavaScriptInIsolatedWorld(ISOLATED_WORLD, [
      { code: `(() => { try { return localStorage.getItem(${JSON.stringify(LOGIN_TOKEN_KEY)}) } catch { return null } })()` },
    ])
    return typeof value === 'string' && value !== '' ? value : undefined
  }
  catch {
    return undefined
  }
}

export interface PairWithPageOptions {
  contents: WebContents
  webUrl: string
  apiBaseUrl: string
  name: string
  appVersion: string
}

/** Cookie byclaw-be's Spring session uses; a login elsewhere on the host (mock login, SSO) may leave only this. */
export const SESSION_COOKIE = 'SESSION'

/**
 * Pair using the page's login. Uses the window session's fetch, so cookies and proxy settings
 * match the page's own requests; with no `Beyond-Token` in the page, a `SESSION` cookie for the API
 * origin is enough (the BE accepts its session).
 * @returns undefined when the page is not logged in yet.
 */
export async function pairWithPage(options: PairWithPageOptions): Promise<PairResult | undefined> {
  if (!isWebOrigin(options.contents.getURL(), options.webUrl)) return undefined
  const session = options.contents.session
  const loginToken = await readLoginToken(options.contents, options.webUrl)
  if (!loginToken) {
    const cookies = await session.cookies.get({ url: options.apiBaseUrl, name: SESSION_COOKIE }).catch(() => [])
    if (cookies.length === 0) return undefined
  }
  return pairDevice({
    baseUrl: options.apiBaseUrl,
    loginToken: loginToken ?? '',
    name: options.name,
    platform: process.platform,
    appVersion: options.appVersion,
    fetch: ((input: string, init?: RequestInit) => session.fetch(input, init)) as typeof fetch,
  })
}
