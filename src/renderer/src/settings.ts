import { bindValidation } from './validation'

function element(id: string): HTMLElement {
  const found = document.getElementById(id)
  if (found === null) {
    throw new Error(`missing #${id}`)
  }
  return found
}

const MAIN_WINDOW_SECTIONS = ['connecting', 'unconfigured', 'reauth', 'load-failed']

// startSettingsPage 渲染设置窗口（?view=settings）：读取当前地址填入输入框并实时校验；
// 保存经主进程校验、写配置并按需让主窗口打开原始地址，成功后页面自行关窗，
// 失败时显示不含 token 的错误并保持打开；取消直接关窗，不改变配置与主窗口。
// 关窗一律由页面自己 window.close()，主进程不在本窗口的 IPC 处理函数里关它。
export async function startSettingsPage(): Promise<void> {
  for (const id of MAIN_WINDOW_SECTIONS) {
    element(id).hidden = true
  }
  const section = element('settings')
  const form = element('settings-form') as HTMLFormElement
  const input = element('settings-url') as HTMLInputElement
  const errorText = element('settings-error')
  const saveButton = element('save') as HTMLButtonElement
  const cancelButton = element('cancel') as HTMLButtonElement

  section.hidden = false
  const validation = bindValidation(input, errorText, saveButton)
  try {
    // 与 Go 版 CurrentURL 一致：未配置或配置无效时为空字符串。
    input.value = await window.dsh.getSettingsURL()
  } catch {
    input.value = ''
  }
  validation.validate()
  input.focus()

  form.addEventListener('submit', async (event) => {
    event.preventDefault()
    if (saveButton.disabled) {
      return
    }
    validation.invalidate()
    input.disabled = true
    errorText.textContent = ''
    const result = await window.dsh.saveSettings(input.value)
    if (!result.ok) {
      input.disabled = false
      // 先按当前输入恢复按钮状态，再显示本次保存的错误。
      validation.validate()
      errorText.textContent = result.error
      return
    }
    window.close()
  })

  cancelButton.addEventListener('click', () => {
    window.close()
  })
}
