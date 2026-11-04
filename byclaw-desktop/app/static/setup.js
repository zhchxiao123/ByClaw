const $ = id => document.getElementById(id)
window.byclawLocal.getState().then(state => {
  $('url').value = state.webUrl
  $('login').value = state.loginUrl
  $('extra').value = state.extraOrigins.join('\n')
  if (state.webUrlFromEnv) $('error').textContent = '当前地址来自环境变量 BYCLAW_DESKTOP_URL，保存的值在去掉该变量后生效。'
})
async function save() {
  const extra = $('extra').value.split('\n').map(s => s.trim()).filter(Boolean)
  const result = await window.byclawLocal.saveWebUrl($('url').value, extra, $('login').value)
  if (!result.ok) $('error').textContent = result.error
}
$('save').addEventListener('click', save)
$('url').addEventListener('keydown', e => { if (e.key === 'Enter') save() })
