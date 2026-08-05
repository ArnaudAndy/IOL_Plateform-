'use strict'

/*
 * OpenHIM Core v8.5.0 applies requestBody/responseBody only to the primary
 * transaction fields. Route and mediator orchestrations are persisted through
 * another code path, so they can still contain the original business payload.
 *
 * This build-time patch extends the channel policy to every persisted copy.
 * Exact-match replacements deliberately make an upstream upgrade fail closed:
 * the patch must be reviewed whenever OpenHIM changes these functions.
 */
const fs = require('node:fs')

const sourceFunction = `function truncateOrchestrationBodies(ctx, orchestrations) {
  return orchestrations.map(orch => {
    const truncatedOrchestration = Object.assign({}, orch)
    if (truncatedOrchestration.request && truncatedOrchestration.request.body) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.request)
    }
    if (
      truncatedOrchestration.response &&
      truncatedOrchestration.response.body
    ) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.response)
    }
    return truncatedOrchestration
  })
}`

const protectedSourceFunction = `function truncateOrchestrationBodies(ctx, orchestrations) {
  return orchestrations.map(orch => {
    const truncatedOrchestration = Object.assign({}, orch, {
      request: orch.request ? Object.assign({}, orch.request) : orch.request,
      response: orch.response ? Object.assign({}, orch.response) : orch.response
    })

    if (ctx.authorisedChannel.requestBody === false && truncatedOrchestration.request) {
      truncatedOrchestration.request.body = ''
    } else if (truncatedOrchestration.request && truncatedOrchestration.request.body) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.request)
    }

    if (ctx.authorisedChannel.responseBody === false && truncatedOrchestration.response) {
      truncatedOrchestration.response.body = ''
    } else if (
      truncatedOrchestration.response &&
      truncatedOrchestration.response.body
    ) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.response)
    }
    return truncatedOrchestration
  })
}`

const compiledFunction = `function truncateOrchestrationBodies(ctx, orchestrations) {
  return orchestrations.map(orch => {
    const truncatedOrchestration = Object.assign({}, orch);
    if (truncatedOrchestration.request && truncatedOrchestration.request.body) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.request);
    }
    if (truncatedOrchestration.response && truncatedOrchestration.response.body) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.response);
    }
    return truncatedOrchestration;
  });
}`

const protectedCompiledFunction = `function truncateOrchestrationBodies(ctx, orchestrations) {
  return orchestrations.map(orch => {
    const truncatedOrchestration = Object.assign({}, orch, {
      request: orch.request ? Object.assign({}, orch.request) : orch.request,
      response: orch.response ? Object.assign({}, orch.response) : orch.response
    });
    if (ctx.authorisedChannel.requestBody === false && truncatedOrchestration.request) {
      truncatedOrchestration.request.body = '';
    } else if (truncatedOrchestration.request && truncatedOrchestration.request.body) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.request);
    }
    if (ctx.authorisedChannel.responseBody === false && truncatedOrchestration.response) {
      truncatedOrchestration.response.body = '';
    } else if (truncatedOrchestration.response && truncatedOrchestration.response.body) {
      utils.enforceMaxBodiesSize(ctx, truncatedOrchestration.response);
    }
    return truncatedOrchestration;
  });
}`

const sourceRoutePolicy = `function storeNonPrimaryResponse(ctx, route, done) {
  // check if channel response body is false and remove
  if (ctx.authorisedChannel.responseBody === false) {
    route.response.body = ''
  }`

const protectedSourceRoutePolicy = `function storeNonPrimaryResponse(ctx, route, done) {
  // Apply the channel policy to both sides of every secondary route.
  if (ctx.authorisedChannel.requestBody === false && route.request) {
    route.request.body = ''
  }
  if (ctx.authorisedChannel.responseBody === false && route.response) {
    route.response.body = ''
  }`

const compiledRoutePolicy = `function storeNonPrimaryResponse(ctx, route, done) {
  // check if channel response body is false and remove
  if (ctx.authorisedChannel.responseBody === false) {
    route.response.body = '';
  }`

const protectedCompiledRoutePolicy = `function storeNonPrimaryResponse(ctx, route, done) {
  // Apply the channel policy to both sides of every secondary route.
  if (ctx.authorisedChannel.requestBody === false && route.request) {
    route.request.body = '';
  }
  if (ctx.authorisedChannel.responseBody === false && route.response) {
    route.response.body = '';
  }`

function replaceExactlyOnce(content, expected, replacement, label) {
  const first = content.indexOf(expected)
  const last = content.lastIndexOf(expected)
  if (first < 0 || first !== last) {
    throw new Error(`${label}: expected exactly one upstream match`)
  }
  return content.slice(0, first) + replacement + content.slice(first + expected.length)
}

function patchFile(file, replacements) {
  let content = fs.readFileSync(file, 'utf8')
  for (const [expected, replacement, label] of replacements) {
    content = replaceExactlyOnce(content, expected, replacement, `${file} (${label})`)
  }
  fs.writeFileSync(file, content)
}

patchFile('/app/src/middleware/messageStore.js', [
  [sourceFunction, protectedSourceFunction, 'orchestration policy'],
  [sourceRoutePolicy, protectedSourceRoutePolicy, 'secondary-route policy']
])

patchFile('/app/lib/middleware/messageStore.js', [
  [compiledFunction, protectedCompiledFunction, 'compiled orchestration policy'],
  [compiledRoutePolicy, protectedCompiledRoutePolicy, 'compiled secondary-route policy']
])

console.log('Applied OpenHIM orchestration body-retention privacy patch.')
