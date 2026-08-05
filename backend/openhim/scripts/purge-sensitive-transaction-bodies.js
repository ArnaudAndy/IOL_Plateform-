/*
 * Run with an explicit, reviewed list:
 *   mongo openhim --quiet --eval \
 *     'var transactionIds=["<mongo-object-id>"]; load("/tmp/purge-sensitive-transaction-bodies.js")'
 *
 * The script intentionally has no "purge everything" mode. Audit metadata,
 * timestamps, statuses and correlation properties remain available. Only
 * payload-bearing fields are cleared and manual rerun is disabled.
 */
if (
  typeof transactionIds === 'undefined' ||
  !Array.isArray(transactionIds) ||
  transactionIds.length === 0
) {
  print('Refusing purge: transactionIds must be a non-empty array.')
  quit(3)
}

var uniqueIds = transactionIds.filter(function (id, index, values) {
  return values.indexOf(id) === index
})

if (uniqueIds.some(function (id) { return !/^[a-fA-F0-9]{24}$/.test(id) })) {
  print('Refusing purge: every transactionIds entry must be a Mongo ObjectId.')
  quit(3)
}

var objectIds = uniqueIds.map(ObjectId)
var selector = {_id: {$in: objectIds}}
var matched = db.transactions.find(selector).count()
if (matched !== objectIds.length) {
  print('Refusing purge: requested ' + objectIds.length + ' transaction(s), found ' + matched + '.')
  quit(4)
}

var updated = 0
db.transactions.find(selector).forEach(function (transaction) {
  if (transaction.request) transaction.request.body = ''
  if (transaction.response) transaction.response.body = ''

  ;(transaction.orchestrations || []).forEach(function (orchestration) {
    if (orchestration.request) orchestration.request.body = ''
    if (orchestration.response) orchestration.response.body = ''
  })

  ;(transaction.routes || []).forEach(function (route) {
    if (route.request) route.request.body = ''
    if (route.response) route.response.body = ''
  })

  db.transactions.replaceOne(
    {_id: transaction._id},
    Object.assign(transaction, {canRerun: false})
  )
  updated += 1
})

print('Sanitized OpenHIM transactions: ' + updated)
