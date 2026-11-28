const assert = require('node:assert/strict')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core')

const origin = process.env.PEANUT_PATROL_TEST_URL
const token = process.env.PEANUT_PATROL_TEST_TOKEN
if (process.env.PEANUT_DISPOSABLE_PATROL_TEST !== 'true' || !origin || !token) {
  throw new Error('Specify the isolated patrol test environment and its administrator token')
}
const name = 'qa-stage-a-' + Date.now()
const coordinates = [[115.456,36.123],[115.460,36.123],[115.460,36.126],[115.456,36.123]]

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.PEANUT_TEST_CHROMIUM })
  const page = await browser.newPage({ viewport:{width:1440,height:950},timezoneId:'Asia/Shanghai' })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const devices = () => page.getByRole('heading',{name:'设备管理',exact:true})

  async function authorize() {
    await page.getByRole('button',{name:'访问权限',exact:true}).click()
    const dialog=page.getByRole('dialog',{name:'设备访问权限'})
    await dialog.locator('#iot-token').fill(token)
    await dialog.getByRole('button',{name:'应用权限'}).click()
    await dialog.waitFor({state:'hidden'})
    await page.getByText('管理员权限',{exact:true}).waitFor()
  }

  async function field(dialog) {
    const combo=page.getByRole('combobox',{name:'选择所属地块',exact:true})
    await dialog.locator('.el-select').filter({has:combo}).click()
    await combo.fill(name)
    await page.getByRole('option',{name,exact:true}).click()
  }

  async function responseFor(path, action) {
    const pending=page.waitForResponse(response=>new URL(response.url()).pathname===path
      && ['POST','PUT','DELETE'].includes(response.request().method()))
    await action()
    const response=await pending
    assert(response.ok(),await response.text())
    return (await response.json()).data
  }

  try {
    const plot = await page.request.post(origin+'/api/fields',{data:{
      name,geometry:JSON.stringify({type:'Polygon',coordinates:[coordinates]})}})
    assert(plot.ok(),await plot.text())
    await page.goto(origin+'/#/patrol/devices')
    await devices().waitFor()
    await page.getByText('只读访问',{exact:true}).waitFor()
    assert(await page.getByRole('button',{name:'添加设备',exact:true}).isDisabled())
    await authorize()

    await page.getByRole('button',{name:'添加设备',exact:true}).click()
    let dialog=page.getByRole('dialog',{name:'添加设备'})
    await dialog.locator('#wizard-type option[value="weather_station"]').waitFor({state:'attached'})
    await dialog.locator('#wizard-type').selectOption('weather_station')
    await dialog.getByRole('button',{name:'下一步'}).click()
    await dialog.locator('#wizard-name').fill(name+' 气象站')
    await dialog.locator('#wizard-sn').fill(name+'-weather')
    await dialog.getByRole('button',{name:'下一步'}).click()
    await dialog.locator('#wizard-mode').selectOption('simulated')
    await field(dialog)
    await dialog.getByRole('button',{name:'下一步'}).click()
    assert(await dialog.getByRole('checkbox',{name:'空气温度',exact:false}).isChecked())
    await dialog.getByRole('button',{name:'下一步'}).click()
    const station=await responseFor('/api/patrol/devices',()=>dialog.getByRole('button',{name:'保存设备'}).click())
    assert.equal(station.deviceType,'weather_station')
    assert.equal(station.accessMode,'simulated')
    await dialog.waitFor({state:'hidden'})
    await page.getByRole('row').filter({hasText:name+' 气象站'}).getByText('模拟数据',{exact:true}).waitFor()

    await page.getByRole('link',{name:'模拟设备实验室',exact:true}).click()
    await page.getByRole('heading',{name:'模拟设备实验室'}).waitFor()
    await page.getByRole('button',{name:'创建模拟设备',exact:true}).click()
    dialog=page.getByRole('dialog',{name:'创建模拟设备'})
    await dialog.locator('#sim-template').selectOption('soil_multi_sensor')
    await dialog.locator('#sim-name').fill(name+' 土壤站')
    await field(dialog)
    await dialog.locator('#sim-create-interval').fill('5')
    const entry=await responseFor('/api/iot/simulator/devices',()=>dialog.getByRole('button',{name:'登记模拟设备'}).click())
    const id=entry.device.id
    await dialog.waitFor({state:'hidden'})
    await page.getByRole('heading',{name:name+' 土壤站'}).waitFor()
    await responseFor('/api/iot/simulator/devices/'+id+'/start',()=>page.getByRole('button',{name:'启动场景'}).click())
    await page.getByRole('img',{name:/模拟趋势/}).waitFor({timeout:22000})
    await responseFor('/api/iot/simulator/devices/'+id+'/pause',()=>page.getByRole('button',{name:'暂停',exact:true}).click())
    await page.getByText('已暂停',{exact:false}).first().waitFor()
    await responseFor('/api/iot/simulator/devices/'+id+'/resume',()=>page.getByRole('button',{name:'继续',exact:true}).click())
    await responseFor('/api/iot/simulator/devices/'+id+'/stop',()=>page.getByRole('button',{name:'停止',exact:true}).click())
    await page.goto(origin+'/#/patrol/devices/'+id)
    await page.getByRole('heading',{name:name+' 土壤站'}).waitFor()
    await page.getByText('模拟数据',{exact:true}).waitFor()
    await page.getByRole('definition').filter({hasText:/\d+ (pH|°C|mg\/kg|%VWC)/}).first().waitFor()

    await page.goto(origin+'/#/patrol/devices')
    await devices().waitFor()
    for(const width of [768,390]){
      await page.setViewportSize({width,height:844})
      assert(await page.getByRole('button',{name:'添加设备',exact:true}).isVisible())
      assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'page overflow at '+width)
    }
    await page.goto(origin+'/#/patrol/simulator')
    await page.getByRole('heading',{name:'模拟设备实验室'}).waitFor()
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'simulator overflows mobile')
    assert.deepEqual(errors,[])
    console.log('Edge: read-only permissions, five-step weather registration, simulator creation and controls, InfluxDB chart, device detail, 1440/768/390 layouts passed.')
  } catch(error) {
    if(process.env.PEANUT_TEST_SCREENSHOT) await page.screenshot({path:process.env.PEANUT_TEST_SCREENSHOT,fullPage:true})
    throw error
  } finally {
    await browser.close()
  }
}
main().catch(error=>{console.error(error);process.exitCode=1})
