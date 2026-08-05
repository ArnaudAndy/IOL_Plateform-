import assert from 'node:assert/strict'
import { existsSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const appUrl = process.env.APP_URL || 'http://localhost'
const artifactsDir = resolve('tests/artifacts')
const chromeCandidates = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
].filter(Boolean)
const executablePath = chromeCandidates.find((candidate) => existsSync(candidate))

assert(executablePath, 'Chrome or Edge was not found.')
mkdirSync(artifactsDir, { recursive: true })

const domains = ['HEALTH', 'FINANCE', 'EDUCATION', 'RETAIL', 'LOGISTICS', 'COMPLIANCE', 'CUSTOM']
const databaseTypes = ['POSTGRES', 'MYSQL', 'MARIADB', 'MSSQL', 'ORACLE', 'SQLITE', 'SNOWFLAKE', 'REDSHIFT']
const standards = Array.from({ length: 13 }, (_, index) => ({
  id: `standard-${index + 1}`,
  name: `Norme ${String(index + 1).padStart(2, '0')}`,
  domain: domains[index % domains.length],
  description: `Description ${index + 1}`,
  status: index % 3 === 0 ? 'ACTIVE' : 'DRAFT',
  version: `1.${index}`,
  termCount: 0,
}))
const connections = Array.from({ length: 13 }, (_, index) => ({
  id: `connection-${index + 1}`,
  name: `Connexion ${String(index + 1).padStart(2, '0')}`,
  dbType: databaseTypes[index % databaseTypes.length],
  host: 'database.internal',
  port: 5400 + index,
  database: `database_${index + 1}`,
  username: 'etl_user',
}))

function json(route, data, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(data),
  })
}

function envelope(data, message = 'OK') {
  return { message, data }
}

async function mockApi(route) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname
  const method = request.method()

  if (path === '/api/v1/standards') {
    if (method === 'POST') {
      const body = request.postDataJSON()
      assert(domains.includes(body.domain), `Unexpected standard domain: ${body.domain}`)
      const created = {
        id: `standard-created-${Date.now()}`,
        status: 'DRAFT',
        termCount: 0,
        ...body,
      }
      standards.unshift(created)
      return json(route, created, 201)
    }
    return json(route, standards)
  }
  if (/^\/api\/v1\/standards\/[^/]+\/terms$/.test(path)) return json(route, [])
  if (path === '/api/v1/audit' || path.startsWith('/api/v1/audit/')) return json(route, [])

  if (path === '/api/connections') return json(route, envelope(connections))
  if (path.startsWith('/api/connections/')) return json(route, envelope('Connexion OK'))

  if (path === '/api/workflows') return json(route, envelope([]))
  if (path === '/api/logs/interop/summary') {
    return json(route, envelope({ total: 0, success: 0, failed: 0, dlqCount: 0, last24h: 0 }))
  }
  if (path === '/api/logs' || path === '/api/logs/interop') return json(route, envelope([]))
  if (path === '/api/ai/status') {
    return json(route, envelope({
      configured: true,
      privacyMode: 'SCHEMA_ONLY',
      strategy: 'ROUND_ROBIN_FAILOVER',
    }))
  }
  if (path === '/api/users') return json(route, envelope([]))
  if (path.startsWith('/api/auth/')) {
    return json(route, envelope({
      token: 'e2e-token',
      userId: 'e2e-admin',
      name: 'E2E Admin',
      email: 'e2e@iol.test',
      role: 'ADMIN',
    }))
  }

  return json(route, envelope(method === 'GET' ? [] : {}))
}

const browser = await chromium.launch({ executablePath, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
await context.addInitScript(() => {
  window.localStorage.setItem('iol.etl.token', 'e2e-token')
  window.localStorage.setItem('iol.etl.user', JSON.stringify({
    id: 'e2e-admin',
    email: 'e2e@iol.test',
    name: 'E2E Admin',
    role: 'ADMIN',
    active: true,
  }))
})
await context.route('**/api/**', mockApi)

const page = await context.newPage()
const runtimeErrors = []
page.on('pageerror', (error) => runtimeErrors.push(error.message))

try {
  await page.goto(`${appUrl}/standards`, { waitUntil: 'networkidle' })
  await page.getByText('Norme 01', { exact: true }).waitFor()
  await page.getByText('1 / 3', { exact: true }).waitFor()
  await page.getByLabel('Page suivante').click()
  await page.getByText('Norme 07', { exact: true }).waitFor()

  const standardSearch = page.getByPlaceholder('Rechercher une norme')
  await standardSearch.fill('Norme 13')
  await page.getByText('Norme 13', { exact: true }).waitFor()
  await standardSearch.clear()

  await page.getByRole('button', { name: /Nouvelle norme/ }).click()
  const standardDialog = page.getByRole('dialog')
  await standardDialog.locator('input').first().fill('Norme finance E2E')
  await standardDialog.getByRole('combobox').click()
  await page.getByRole('option', { name: 'Finance' }).click()
  await standardDialog.getByRole('button', { name: 'Créer', exact: true }).click()
  await standardDialog.waitFor({ state: 'hidden' })
  await page.getByText('Norme finance E2E', { exact: true }).first().waitFor()
  await page.screenshot({ path: resolve(artifactsDir, 'standards-desktop.png'), fullPage: true })

  await page.reload({ waitUntil: 'networkidle' })
  await page.getByText('Normes & champs', { exact: true }).waitFor()
  assert.equal(new URL(page.url()).pathname, '/standards', 'A refresh changed the active route.')

  await page.goto(`${appUrl}/connections`, { waitUntil: 'networkidle' })
  await page.getByText(/1.10 sur 13/).waitFor()
  const postgresClass = await page.getByText('POSTGRES', { exact: true }).first().getAttribute('class')
  const mysqlClass = await page.getByText('MYSQL', { exact: true }).first().getAttribute('class')
  assert(postgresClass?.includes('sky'), 'POSTGRES does not have its expected color tag.')
  assert(mysqlClass?.includes('amber'), 'MYSQL does not have its expected color tag.')
  await page.getByLabel('Page suivante').click()
  await page.getByText('Connexion 11', { exact: true }).waitFor()
  await page.screenshot({ path: resolve(artifactsDir, 'connections-desktop.png'), fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload({ waitUntil: 'networkidle' })
  const viewportFits = await page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
  )
  assert(viewportFits, 'The connections page overflows the mobile viewport.')
  await page.screenshot({ path: resolve(artifactsDir, 'connections-mobile.png'), fullPage: true })

  const routes = [
    '/',
    '/monitoring',
    '/workflows',
    '/workflows/new',
    '/executions',
    '/interop',
    '/standards',
    '/connections',
    '/sql',
    '/assistant-sql',
    '/utilisateurs',
    '/audit',
    '/parametres',
  ]
  for (const path of routes) {
    console.log(`Checking route ${path}`)
    await page.goto(`${appUrl}${path}`, { waitUntil: 'networkidle' })
    const main = page.locator('main')
    const mainCount = await main.count()
    assert(mainCount > 0, `Route ${path} did not render the authenticated application shell. Current URL: ${page.url()}`)
    const content = (await main.innerText()).trim()
    assert(content.length > 0, `Route ${path} rendered an empty main area.`)
  }

  assert.deepEqual(runtimeErrors, [], `Browser errors: ${runtimeErrors.join(' | ')}`)
  console.log(`Frontend smoke passed: ${routes.length} routes, standards pagination/create, connection tags/pagination, mobile viewport.`)
  console.log(`Screenshots: ${artifactsDir}`)
} finally {
  await context.close()
  await browser.close()
}
