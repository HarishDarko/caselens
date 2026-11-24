import type { Plugin } from 'vite'

// Cloudflare Web Analytics: a cookieless page-view beacon. The token is public (it names the site, it does not
// authorise anything), so it belongs in the page. Only the production build gets the tag, so dev, tests and the
// local server never call Cloudflare.
export const CF_BEACON_TOKEN = 'a3f64b6da79b4a66a1a3010d35437e61'

export const beaconTag = (token: string) =>
  `<script defer src="https://static.cloudflareinsights.com/beacon.min.js" data-cf-beacon='{"token": "${token}"}'></script>`

export function cloudflareBeacon(token = CF_BEACON_TOKEN): Plugin {
  return {
    name: 'cloudflare-web-analytics',
    apply: 'build',
    transformIndexHtml: { order: 'post', handler: (html) => html.replace('</body>', `${beaconTag(token)}</body>`) },
  }
}
