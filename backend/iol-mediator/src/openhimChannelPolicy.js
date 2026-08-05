'use strict'

const {buildOpenHimOptions, requestJson} = require('./openhim')

function matchingChannel(channels, targetUrl) {
  const {pathname} = new URL(targetUrl)
  return (channels || [])
    .filter(channel => channel && channel.status === 'enabled')
    .filter(channel => !Array.isArray(channel.methods) || channel.methods.includes('POST'))
    .filter(channel => {
      try {
        return new RegExp(channel.urlPattern).test(pathname)
      } catch (_error) {
        return false
      }
    })
    .sort((left, right) => Number(left.priority || 100) - Number(right.priority || 100))[0]
}

/**
 * OpenHIM reruns bypass the Kafka worker and its MongoDB ledger. This runtime
 * check therefore permits an egress channel only when OpenHIM cannot retain
 * the POST body required for a manual rerun.
 */
function createOpenHimChannelPolicy(config, {
  requester = requestJson,
  cacheTtlMs = 30000
} = {}) {
  const options = buildOpenHimOptions(config)
  let cachedChannels = null
  let cachedAt = 0

  async function channels() {
    const now = Date.now()
    if (cachedChannels && now - cachedAt < cacheTtlMs) return cachedChannels
    cachedChannels = await requester({options, path: '/channels'})
    cachedAt = now
    return cachedChannels
  }

  return {
    async assertNonReplayable(targetUrl) {
      if (!config.openhimUsername || !config.openhimPassword) {
        throw new Error(
          'OpenHIM credentials are required to verify the OUTBOUND channel policy'
        )
      }
      const channel = matchingChannel(await channels(), targetUrl)
      if (!channel) {
        throw new Error('No enabled OpenHIM channel matches the OUTBOUND destination')
      }
      if (channel.requestBody !== false || channel.responseBody !== false) {
        throw new Error(
          `OpenHIM channel ${channel.name || channel._id} retains transaction bodies`
        )
      }
      return channel
    }
  }
}

module.exports = {
  createOpenHimChannelPolicy,
  matchingChannel
}
