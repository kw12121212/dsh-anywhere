import type { LocalState } from '../../main/view-state'
import { REASON_TEXT } from './reasons'

const sections = {
  connecting: element('connecting'),
  unconfigured: element('unconfigured'),
  reauth: element('reauth'),
  error: element('load-failed')
}
const retryButton = element('retry') as HTMLButtonElement
const errorText = element('failed-error')

function element(id: string): HTMLElement {
  const found = document.getElementById(id)
  if (found === null) {
    throw new Error(`missing #${id}`)
  }
  return found
}

function render(state: LocalState): void {
  for (const [view, section] of Object.entries(sections)) {
    section.hidden = view !== state.view
  }
  if (state.view === 'unconfigured') {
    element('invalid-config').hidden = !state.invalidConfig
  }
  if (state.view === 'error') {
    element('failed-url').textContent = state.url
    element('failed-reason').textContent = REASON_TEXT[state.reason]
    errorText.textContent = ''
    retryButton.disabled = false
    retryButton.focus()
  }
}

retryButton.addEventListener('click', () => {
  retryButton.disabled = true
  errorText.textContent = ''
  element('failed-reason').textContent = '正在重新连接…'
  window.dsh.retry().catch(() => {
    retryButton.disabled = false
    errorText.textContent = '重试失败，请稍后再试。'
  })
})

// “修改地址”与“打开设置”打开设置窗口的动作由后续设置窗口接通。

window.dsh.onState(render)
void window.dsh.getState().then(render)
