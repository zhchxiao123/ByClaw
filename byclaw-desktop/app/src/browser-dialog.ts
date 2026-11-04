/** Texts of the native confirmation dialog for device_browser (no Electron import: unit-tested). */

import { DECISION_LABELS, type ConfirmRequest, type Decision } from '@byclaw/node-runtime'

export interface DialogText {
  message: string
  detail: string
  buttons: string[]
}

const BUTTONS: Record<Decision, string> = {
  ...DECISION_LABELS,
  allow_session: '本会话内允许这个网站',
}

function lines(...parts: Array<string | false | undefined>): string {
  return parts.filter((p): p is string => typeof p === 'string').join('\n')
}

/** Message, detail and button labels for a browser prompt; undefined for other tools. */
export function browserDialogText(request: ConfirmRequest): DialogText | undefined {
  const b = request.browser
  if (!b) return undefined
  const site = b.site || '空白页'
  const buttons = request.options.map(option => BUTTONS[option])
  const footer = lines('', `设备：${request.device.name}　·　浏览器会话：${b.session}`)
  switch (b.action) {
    case 'bind':
      return {
        message: 'AI 请求接管你当前的 Chrome 标签页',
        detail: lines(
          `标题：${b.title || '（无标题）'}`,
          `网址：${b.url ?? ''}`,
          '',
          '接管后，AI 可以读取并操作这个标签页（用的是你在 Chrome 里的登录）。',
          request.options.includes('allow_session') ? `「${BUTTONS.allow_session}」：这次对话里再访问 ${site} 时不再询问。` : undefined,
          footer,
        ),
        buttons,
      }
    case 'eval':
      return {
        message: `AI 请求在 ${site} 上运行脚本`,
        detail: lines('脚本：', b.command.replace(/^eval\s+/, ''), '', '脚本可以读取这个网页上的一切内容。每次运行都会询问。', footer),
        buttons,
      }
    case 'upload':
      return {
        message: `AI 请求向 ${site} 上传文件`,
        detail: lines('文件：', ...(b.files ?? []).map(f => `  ${f}`), '', `命令：${b.command}`, '每次上传都会询问。', footer),
        buttons,
      }
    default:
      return {
        message: `AI 请求在你的 Chrome 里访问 ${site}`,
        detail: lines(
          b.url ? `网址：${b.url}` : undefined,
          `命令：${b.command}`,
          '',
          request.options.includes('allow_session') ? `「${BUTTONS.allow_session}」：这次对话里在 ${site} 上的浏览、点击和输入都不再询问；运行脚本和上传文件仍会每次询问。` : undefined,
          footer,
        ),
        buttons,
      }
  }
}
