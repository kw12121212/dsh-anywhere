import { URLValidationError, normalizeHarnessURL } from '../../main/url-validation'

export interface Validation {
  // 按当前输入重新校验并更新错误文字与按钮状态。
  validate(): void
  // 提交期间作废按钮，直到下一次 validate 恢复。
  invalidate(): void
}

// bindValidation 在输入时实时校验地址，与主进程保存校验共用同一实现：
// 空输入禁用按钮且不显示错误；非法地址显示固定原因（不含 token）并禁用；合法地址启用。
export function bindValidation(input: HTMLInputElement, errorText: HTMLElement, button: HTMLButtonElement): Validation {
  const validate = (): void => {
    const raw = input.value
    if (raw.trim() === '') {
      errorText.textContent = ''
      button.disabled = true
      return
    }
    try {
      normalizeHarnessURL(raw)
      errorText.textContent = ''
      button.disabled = false
    } catch (err) {
      errorText.textContent = err instanceof URLValidationError ? err.message : '地址格式无效'
      button.disabled = true
    }
  }
  input.addEventListener('input', validate)
  return { validate, invalidate: () => (button.disabled = true) }
}
