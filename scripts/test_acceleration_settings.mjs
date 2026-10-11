/** Isolated device-selection browser regression; no warehouse or real backend is contacted. */
import assert from 'node:assert/strict';
const { chromium } = await import(process.env.MARGIN_PLAYWRIGHT_PATH || 'playwright');
const browser = await chromium.launch({ headless: true, ...(process.env.MARGIN_BROWSER_PATH ? { executablePath: process.env.MARGIN_BROWSER_PATH } : {}) });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', route => route.fulfill({status:503,contentType:'application/json',body:'{"message":"Isolated fixture"}'}));
  const report = (stage, family) => ({stage,requested:family,actual:family,device:null,fallbackReason:null,status:'AVAILABLE'});
  const capabilities = {
    cpu:report('FGW','JAVA_CPU'),cuda:report('FGW','CUDA'),opencl:report('FGW','OPENCL'),
    stages:{cuda:{frontier:report('FRONTIER','CUDA'),fgw:report('FGW','CUDA')},opencl:{frontier:report('FRONTIER','OPENCL'),fgw:report('FGW','OPENCL')}},
    devices:{cuda:[{id:'0',name:'CUDA fixture GPU',frontier:true,fgw:true}],opencl:[{id:'1:2',name:'OpenCL fixture GPU',frontier:true,fgw:true},{id:'0:0',name:'Legacy compatible GPU',frontier:true,fgw:true}]}
  };
  await page.route('**/api/v1/analysis/capabilities', route => route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(capabilities)}));
  await page.goto((process.env.MARGIN_FRONTEND_URL || 'http://127.0.0.1:5179') + '/settings');
  await page.getByText('Calculation and acceleration',{exact:false}).click();
  await page.getByRole('switch').check();
  await page.getByLabel('Backend GPU vendor').selectOption('nvidia');
  await page.getByRole('option',{name:'CUDA fixture GPU (0)',exact:true}).waitFor({state:'attached'});
  await page.getByLabel('Backend GPU device').selectOption('0');
  await page.getByRole('button',{name:'Save calculation preferences'}).click();
  let saved = await page.evaluate(() => JSON.parse(localStorage.getItem('margintrace.acceleration')));
  assert.equal(saved.deviceId,'0'); assert.equal(saved.isNvidia,true);
  await page.getByLabel('Backend GPU vendor').selectOption('other');
  assert.equal(await page.getByLabel('Backend GPU device').inputValue(),'');
  await page.getByLabel('Backend GPU device').selectOption('1:2');
  await page.getByRole('button',{name:'Save calculation preferences'}).click();
  saved = await page.evaluate(() => JSON.parse(localStorage.getItem('margintrace.acceleration')));
  assert.equal(saved.deviceId,'1:2'); assert.equal(saved.isNvidia,false);
  await page.reload(); await page.getByText('Calculation and acceleration',{exact:false}).click();
  assert.equal(await page.getByLabel('Backend GPU device').inputValue(),'1:2');
  await page.getByLabel('Backend GPU device').selectOption('');
  await page.getByRole('button',{name:'Save calculation preferences'}).click();
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('margintrace.acceleration')).deviceId),null);
  await page.evaluate(() => localStorage.setItem('margintrace.acceleration',JSON.stringify({useGpu:true,isNvidia:false,deviceId:'0',allowCpuFallback:false})));
  await page.reload(); await page.getByText('Calculation and acceleration',{exact:false}).click();
  assert.equal(await page.getByLabel('Backend GPU device').inputValue(),'0:0');
  await page.getByRole('button',{name:'Save calculation preferences'}).click();
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('margintrace.acceleration')).deviceId),'0:0');
  assert.deepEqual(errors,[]);
  console.log('PASS: backend device inventory, vendor change, persistence, automatic selection and legacy OpenCL IDs');
} finally { await browser.close(); }
