const $ = id => document.getElementById(id)
const DECISIONS = { allow_once: '允许一次', allow_session: '本会话内允许', allow_always: '始终允许', deny: '拒绝', session: '本会话已允许', always: '已始终允许' }
const OUTCOMES = { ok: '完成', denied: '已拒绝', not_allowed: '不在授权范围', timeout: '超时', failed: '失败', cancelled: '已取消' }
const TOOLS = { device_shell: '命令', device_files: '文件' }
async function load() {
  const entries = await window.byclawLocal.auditRecent()
  const rows = $('rows')
  rows.replaceChildren()
  for (const e of entries.reverse()) {
    const tr = document.createElement('tr')
    const cells = [
      new Date(e.ts).toLocaleString(),
      TOOLS[e.tool] || e.tool,
      e.summary + (e.cwd ? `\n（目录 ${e.cwd}）` : ''),
      DECISIONS[e.decision] || e.decision || '—',
      (OUTCOMES[e.outcome] || e.outcome) + (e.reason ? `：${e.reason}` : ''),
    ]
    cells.forEach((text, i) => {
      const td = document.createElement('td')
      td.textContent = text
      if (i === 2) td.className = 'summary'
      if (i === 4) td.className = e.outcome === 'ok' ? 'ok' : 'bad'
      tr.append(td)
    })
    rows.append(tr)
  }
  if (entries.length === 0) rows.innerHTML = '<tr><td colspan="5" class="muted">还没有记录</td></tr>'
}
$('folder').addEventListener('click', () => window.byclawLocal.openAuditFolder())
$('reload').addEventListener('click', load)
load()
