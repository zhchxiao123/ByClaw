const $ = id => document.getElementById(id)
function render(state) {
  $('status').textContent = `状态：${state.status}${state.deviceId ? `　·　设备 ID：${state.deviceId}` : ''}`
  if (document.activeElement !== $('name')) $('name').value = state.deviceName
  $('login').checked = state.launchAtLogin
  $('web').textContent = state.webUrl || '（未设置）'
  const chrome = state.chrome || { state: 'unavailable', label: '不可用', extensionDir: '' }
  $('chromeStatus').textContent = chrome.label
  $('chromeStatus').className = chrome.state === 'connected' ? 'ok' : chrome.state === 'unavailable' ? 'bad' : ''
  $('extensionDir').textContent = chrome.extensionDir ? `扩展目录：${chrome.extensionDir}${chrome.version ? `（byCLI ${chrome.version}）` : ''}` : ''
  $('openExtension').disabled = !chrome.extensionDir
  // Once connected the steps are noise; the folder stays reachable for reinstalling.
  $('chromeSteps').hidden = chrome.state === 'connected'
  $('copyExtension').disabled = !chrome.extensionDir
  const list = $('dirs')
  list.replaceChildren()
  if (state.allowedDirs.length === 0) {
    const li = document.createElement('li')
    li.className = 'muted'
    li.textContent = '还没有授权目录。AI 第一次需要时会请你选择。'
    list.append(li)
  }
  for (const dir of state.allowedDirs) {
    const li = document.createElement('li')
    const span = document.createElement('span')
    span.textContent = dir
    const remove = document.createElement('button')
    remove.textContent = '移除'
    remove.addEventListener('click', async () => render(await window.byclawLocal.removeDir(dir)))
    li.append(span, remove)
    list.append(li)
  }
}
window.byclawLocal.getState().then(render)
window.byclawLocal.onState(render)
$('rename').addEventListener('click', async () => {
  const result = await window.byclawLocal.rename($('name').value)
  $('error').textContent = result.ok ? '' : result.error
})
$('add').addEventListener('click', async () => render(await window.byclawLocal.addDir()))
$('login').addEventListener('change', async () => render(await window.byclawLocal.setLaunchAtLogin($('login').checked)))
$('changeUrl').addEventListener('click', () => { location.href = 'setup.html' })
$('openExtension').addEventListener('click', async () => {
  const error = await window.byclawLocal.openExtensionDir()
  $('chromeNote').textContent = error ? `打不开：${error}` : ''
})
$('copyExtension').addEventListener('click', async () => {
  $('chromeNote').textContent = (await window.byclawLocal.copyExtensionDir()) ? '已复制' : ''
})
