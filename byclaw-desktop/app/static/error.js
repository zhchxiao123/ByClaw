const q = new URLSearchParams(location.search)
const url = q.get('url') || ''
document.getElementById('what').textContent = `地址：${url}`
document.getElementById('detail').textContent = `${q.get('desc') || '加载失败'}（${q.get('code') || '?'}）。请检查网络或代理设置。`
document.getElementById('retry').addEventListener('click', () => { if (/^https?:\/\//.test(url)) location.href = url })
