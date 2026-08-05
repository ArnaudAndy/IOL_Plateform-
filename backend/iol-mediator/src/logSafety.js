'use strict'

/*
 * Error objects from HTTP libraries contain sockets, full request headers and
 * sometimes payloads. Logging only this bounded summary prevents credentials
 * or business data from leaking through nested diagnostic objects.
 */
function safeErrorSummary(error) {
  const rawMessage = error && error.message ? error.message : String(error || 'Unknown error')
  const message = rawMessage
    .replace(/\b(Basic|Bearer)\s+[A-Za-z0-9._~+/=-]+/gi, '$1 [REDACTED]')
    .replace(/(authorization\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]')
    .slice(0, 1000)

  return {
    name: error && error.name ? String(error.name) : 'Error',
    code: error && error.code ? String(error.code) : undefined,
    message
  }
}

module.exports = {
  safeErrorSummary
}
