import type { LocalState } from '../../main/view-state'
import { startSettingsPage } from './settings'
import { REASON_TEXT } from './reasons'
import { bindValidation } from './validation'

function element(id: string): HTMLElement {
  const found = document.getElementById(id)
  if (found === null) {
    throw new Error(`missing #${id}`)
  }
  return found
}

const sections = {
  connecting: element('connecting'),
  unconfigured: element('unconfigured'),
  reauth: element('reauth'),
  error: element('load-failed')
}
const retryButton = element('retry') as HTMLButtonElement
const changeUrlButton = element('change-url') as HTMLButtonElement
const openSettingsButton = element('open-settings') as HTMLButtonElement
const errorText = element('failed-error')
const reauthError = element('reauth-error')
const setupForm = element('setup-form') as HTMLFormElement
const harnessUrlInput = element('harness-url') as HTMLInputElement
const urlError = element('url-error')
const startButton = element('start') as HTMLButtonElement
const statusText = element('status')

function showSection(id: keyof typeof sections): void {
  for (const [view, section] of Object.entries(sections)) {
    section.hidden = view !== id
  }
}

function render(state: LocalState): void {
  switch (state.view) {
    case 'connecting':
      showSection('connecting')
      break
    case 'unconfigured':
      showSection('unconfigured')
      element('invalid-config').hidden = !state.invalidConfig
      harnessUrlInput.focus()
      break
    case 'reauth':
      showSection('reauth')
      openSettingsButton.focus()
      break
    case 'error':
      showSection('error')
      element('failed-url').textContent = state.url
      element('failed-reason').textContent = REASON_TEXT[state.reason]
      errorText.textContent = ''
      retryButton.disabled = false
      retryButton.focus()
      break
  }
}

// 初始化表单：输入实时校验，提交经主进程保存干净地址并用原始地址打开主窗口一次
// （first-run-setup 主规格 complete-setup）；失败时显示不含 token 的错误并恢复输入。
const setupValidation = bindValidation(harnessUrlInput, urlError, startButton)
setupForm.addEventListener('submit', async (event) => {
  event.preventDefault()
  if (startButton.disabled) {
    return
  }
  setupValidation.invalidate()
  harnessUrlInput.disabled = true
  urlError.textContent = ''
  const result = await window.dsh.completeSetup(harnessUrlInput.value)
  if (result.ok) {
    // 主窗口正在打开 harness：显示提示，后续由状态推送（错误页或重新认证页）接管。
    statusText.textContent = '正在打开 harness…'
    showSection('connecting')
    return
  }
  harnessUrlInput.disabled = false
  // 先按当前输入恢复按钮状态，再显示本次提交的错误。
  setupValidation.validate()
  urlError.textContent = result.error
  harnessUrlInput.focus()
})

retryButton.addEventListener('click', () => {
  retryButton.disabled = true
  errorText.textContent = ''
  element('failed-reason').textContent = '正在重新连接…'
  window.dsh.retry().catch(() => {
    retryButton.disabled = false
    errorText.textContent = '重试失败，请稍后再试。'
  })
})

// 错误页“修改地址”与重新认证页“打开设置”都打开设置窗口（load-failure / session-auth 主规格）。
changeUrlButton.addEventListener('click', () => {
  errorText.textContent = ''
  window.dsh.openSettings().catch(() => {
    errorText.textContent = '无法打开设置窗口，请重试。'
  })
})
openSettingsButton.addEventListener('click', () => {
  reauthError.textContent = ''
  window.dsh.openSettings().catch(() => {
    reauthError.textContent = '无法打开设置窗口，请重试。'
  })
})

const view = new URLSearchParams(window.location.search).get('view')
if (view === 'settings') {
  void startSettingsPage()
} else {
  window.dsh.onState(render)
  void window.dsh.getState().then(render)
}
