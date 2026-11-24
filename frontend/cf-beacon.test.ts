import { describe, expect, it } from 'vitest'
import { beaconTag, cloudflareBeacon, CF_BEACON_TOKEN } from './cf-beacon'

describe('Cloudflare Web Analytics beacon', () => {
  it('is the standard snippet with the public token', () => {
    expect(beaconTag(CF_BEACON_TOKEN)).toBe(`<script defer src="https://static.cloudflareinsights.com/beacon.min.js" data-cf-beacon='{"token": "a3f64b6da79b4a66a1a3010d35437e61"}'></script>`)
  })
  it('is added to the production build only, before </body>', () => {
    const plugin = cloudflareBeacon()
    expect(plugin.apply).toBe('build')
    const hook = plugin.transformIndexHtml as { handler: (html: string) => string }
    expect(hook.handler('<body><div id="root"></div></body>')).toBe(`<body><div id="root"></div>${beaconTag(CF_BEACON_TOKEN)}</body>`)
  })
})
