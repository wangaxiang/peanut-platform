// The browser and MQTT simulator use a disposable Compose project.
const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core')

const origin = process.env.PEANUT_PATROL_TEST_URL
const container = process.env.PEANUT_PATROL_TEST_AI_CONTAINER
if (process.env.PEANUT_DISPOSABLE_PATROL_TEST !== 'true' || !origin || !container?.startsWith('peanut-device-qa-')) {
  throw new Error('Specify the isolated patrol test environment and its AI container')
}
const prefix = 'qa-browser-' + Date.now()
const coordinates = [[115.456,36.123],[115.460,36.123],[115.460,36.126],[115.456,36.126],[115.456,36.123]]
const route = { type: 'FeatureCollection', features: coordinates.slice(0,2).map(point => ({
  type: 'Feature', geometry: { type: 'Point', coordinates: point }, properties: {},
})) }

function heartbeat(serial) {
  execFileSync('docker', ['exec', container, 'python', '-c',
    'from paho.mqtt.publish import single; import sys; single(sys.argv[1],sys.argv[2],qos=1,hostname="emqx")',
    'drone/' + serial + '/status', JSON.stringify({ phase: 'idle', battery: 76, faultCode: 'QA-TEST' })])
}

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.PEANUT_TEST_CHROMIUM,
    args: ['--enable-webgl', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 }, timezoneId: 'Asia/Shanghai' })
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))

  async function choose(dialog, label, option, exact = true) {
    const input = page.getByRole('combobox', { name: label, exact: true })
    await dialog.locator('.el-select').filter({ has: input }).click()
    await page.getByRole('option', { name: option, exact }).click()
  }

  async function save(dialog, button, path) {
    const pending = page.waitForResponse(response => new URL(response.url()).pathname === path
      && ['POST', 'PUT'].includes(response.request().method()))
    await dialog.getByRole('button', { name: button }).click()
    const response = await pending
    assert(response.ok(), await response.text())
    await dialog.waitFor({ state: 'hidden' })
    return (await response.json()).data
  }

  async function add(type, suffix, dock) {
    await page.getByRole('button', { name: '添加设备' }).click()
    const dialog = page.getByRole('dialog')
    if (type !== '无人机') await choose(dialog, '设备类型', type)
    await dialog.locator('#device-name').fill(prefix + suffix)
    await dialog.locator('#device-sn').fill(prefix + suffix)
    await choose(dialog, '选择所属地块', prefix)
    if (dock) await choose(dialog, '关联机库', dock.deviceName, false)
    return save(dialog, '保存设备', '/api/patrol/devices')
  }

  try {
    const field = await page.request.post(origin + '/api/fields', { data: {
      name: prefix, geometry: JSON.stringify({ type: 'Polygon', coordinates: [coordinates] }),
    } })
    assert(field.ok(), await field.text())
    await page.goto(origin + '/#/patrol/devices')
    const dock = await add('机库', '-dock')
    const drone = await add('无人机', '-drone', dock)
    const sensor = await add('巡田传感器', '-sensor')
    assert.equal(drone.parentDeviceId, dock.id)

    let row = page.getByRole('row').filter({ hasText: drone.deviceName })
    await row.getByRole('button', { name: '编辑', exact: true }).click()
    let dialog = page.getByRole('dialog')
    await dialog.locator('#device-name').fill(drone.deviceName + '-edited')
    const edited = await save(dialog, '保存设备', '/api/patrol/devices/' + drone.id)
    row = page.getByRole('row').filter({ hasText: edited.deviceName })
    await row.getByRole('button', { name: '停用', exact: true }).click()
    await row.getByRole('button', { name: '启用', exact: true }).click()
    await row.getByRole('link', { name: edited.deviceName, exact: true }).click()
    await page.getByRole('heading', { name: edited.deviceName }).waitFor()
    await page.getByRole('button', { name: '新增维护记录' }).click()
    dialog = page.getByRole('dialog')
    await dialog.locator('#maint-description').fill('浏览器验收巡检')
    await save(dialog, '保存维护记录', '/api/patrol/devices/' + drone.id + '/maintenance')
    await page.getByText('浏览器验收巡检', { exact: true }).waitFor()
    heartbeat(drone.deviceSn)
    await page.getByText('76%', { exact: true }).waitFor()
    await page.getByText('QA-TEST', { exact: true }).waitFor()

    await page.getByRole('link', { name: '巡田任务', exact: true }).click()
    await page.getByRole('button', { name: '新建巡田任务' }).click()
    dialog = page.getByRole('dialog')
    await dialog.locator('#patrol-name').fill(prefix + '-task')
    await dialog.locator('#patrol-route').fill(JSON.stringify(route))
    await dialog.getByText('仅手动执行', { exact: true }).click()
    heartbeat(drone.deviceSn)
    await choose(dialog, '选择地块', prefix)
    await choose(dialog, '选择无人机', edited.deviceName, false)
    const task = await save(dialog, '创建并启用', '/api/patrol/tasks')
    assert.equal(task.droneDeviceId, drone.id)
    assert.equal(task.dockName, dock.deviceName)

    await page.getByRole('link', { name: '设备管理', exact: true }).click()
    row = page.getByRole('row').filter({ hasText: sensor.deviceName })
    await row.getByRole('button', { name: '退役', exact: true }).click()
    await page.getByRole('button', { name: '确认退役', exact: true }).click()
    await row.getByText('已退役', { exact: true }).waitFor()
    console.log('Edge: all three device types, binding, editing, enable/disable, maintenance, live status, task selection and retirement passed.')

    const fixture = JSON.parse(execFileSync('docker', ['exec', container, 'cat', '/tmp/patrol-device-fixture.json'], { encoding: 'utf8' }))
    await page.getByRole('link', { name: 'NDVI 监测', exact: true }).click()
    const tiles = page.waitForResponse(response => response.ok() && response.url().includes('/ndvi/tiles/'))
    await page.locator('a[href="#/patrol/ndvi/' + fixture.flightId + '"]').click()
    await tiles
    await page.getByText('有效像元', { exact: false }).waitFor()
    assert.equal(await page.getByRole('progressbar').count(), 3)
    await page.goto(origin + '/#/sensors/' + fixture.sensor)
    await page.getByRole('img', { name: '土壤温度和湿度趋势折线图' }).waitFor()
    await page.getByRole('img', { name: '土壤氮磷钾趋势折线图' }).waitFor()
    assert.deepEqual(errors, [])
    console.log('Edge: existing NDVI maps and sensor charts rendered with registered equipment.')
  } catch (error) {
    if (process.env.PEANUT_TEST_SCREENSHOT) await page.screenshot({ path: process.env.PEANUT_TEST_SCREENSHOT, fullPage: true })
    throw error
  } finally {
    await browser.close()
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
