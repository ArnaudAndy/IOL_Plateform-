'use strict'

function secretValue(reference, config, env) {
  if (!reference) throw new Error('Outbound authentication secret reference is required')
  const secrets = config.outboundAuthSecrets || {}
  const value = secrets[reference] || env[reference]
  if (!value) throw new Error(`Outbound authentication secret is not configured: ${reference}`)
  return String(value)
}

function normalizedAuth(auth) {
  if (!auth) return {type: 'NONE'}
  if (typeof auth === 'string') {
    if (auth.startsWith('env:')) return {type: 'BEARER', secretRef: auth.slice(4)}
    if (auth.startsWith('vault:')) {
      throw new Error('vault: references require a Vault provider; use a configured secretRef or env:VAR')
    }
    return {type: 'BEARER', secretRef: auth}
  }
  return {...auth, type: String(auth.type || 'NONE').toUpperCase()}
}

async function oauthToken(auth, config, env, fetchImpl) {
  if (!auth.tokenUrl) throw new Error('OAuth2 tokenUrl is required')
  const clientId = secretValue(auth.clientIdRef, config, env)
  const clientSecret = secretValue(auth.clientSecretRef, config, env)
  const body = new URLSearchParams({grant_type: 'client_credentials'})
  if (auth.scope) body.set('scope', auth.scope)
  const response = await fetchImpl(auth.tokenUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization: `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString('base64')}`
    },
    body: body.toString()
  })
  if (!response.ok) throw new Error(`OAuth2 token request failed: HTTP ${response.status}`)
  const payload = await response.json()
  if (!payload.access_token) throw new Error('OAuth2 response does not contain access_token')
  return payload.access_token
}

async function resolveAuthHeaders(destination, {config = {}, env = process.env, fetchImpl = globalThis.fetch} = {}) {
  const auth = normalizedAuth(destination && destination.auth)
  switch (auth.type) {
    case 'NONE':
      return {}
    case 'BEARER':
      return {Authorization: `Bearer ${secretValue(auth.secretRef, config, env)}`}
    case 'BASIC': {
      const username = secretValue(auth.usernameRef, config, env)
      const password = secretValue(auth.passwordRef, config, env)
      return {Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`}
    }
    case 'API_KEY': {
      const header = auth.header || 'X-API-Key'
      if (!/^[A-Za-z0-9-]+$/.test(header)) throw new Error('Invalid API key header name')
      return {[header]: `${auth.prefix || ''}${secretValue(auth.secretRef, config, env)}`}
    }
    case 'OAUTH2_CLIENT_CREDENTIALS':
      return {Authorization: `Bearer ${await oauthToken(auth, config, env, fetchImpl)}`}
    default:
      throw new Error(`Unsupported outbound authentication type: ${auth.type}`)
  }
}

module.exports = {normalizedAuth, resolveAuthHeaders, secretValue}
