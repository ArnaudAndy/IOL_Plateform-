/*
 * Read-only OpenHIM transaction privacy audit.
 *
 * Exit code 2 means at least one stored transaction still contains a payload
 * or can be manually rerun. Metadata and correlation properties are ignored.
 */
var result = {
  transactions: 0,
  requestBodies: 0,
  responseBodies: 0,
  orchestrationBodies: 0,
  routeBodies: 0,
  rerunnableTransactions: 0
}

function hasBody(envelope) {
  return Boolean(envelope && typeof envelope.body === 'string' && envelope.body.length > 0)
}

db.transactions.find({}).forEach(function (transaction) {
  result.transactions += 1
  if (hasBody(transaction.request)) result.requestBodies += 1
  if (hasBody(transaction.response)) result.responseBodies += 1
  if (transaction.canRerun === true) result.rerunnableTransactions += 1

  ;(transaction.orchestrations || []).forEach(function (orchestration) {
    if (hasBody(orchestration.request) || hasBody(orchestration.response)) {
      result.orchestrationBodies += 1
    }
  })
  ;(transaction.routes || []).forEach(function (route) {
    if (hasBody(route.request) || hasBody(route.response)) {
      result.routeBodies += 1
    }
  })
})

printjson(result)

if (
  result.requestBodies > 0 ||
  result.responseBodies > 0 ||
  result.orchestrationBodies > 0 ||
  result.routeBodies > 0 ||
  result.rerunnableTransactions > 0
) {
  quit(2)
}
