import type { ConfigLoad } from './config'

export interface SettingsSaveInput {
  // 保存前已加载的配置；missing 或 invalid 都意味着主窗口需要用原始地址打开。
  previous: ConfigLoad
  // 保存后的干净地址（去掉 token）。
  clean: string
  // 去掉首尾空白的原始输入，可能带 token。
  trimmedInput: string
  // 主窗口当前是否正显示远程 harness 页面。
  mainShowsHarness: boolean
}

// shouldOpenRemoteAfterSettingsSave 判定设置保存成功后是否让主窗口用原始地址打开一次。
// 四个条件任一成立即打开：前次配置无效或缺失、干净地址已变更、原始输入与干净地址不同
// （通常是带 token）、主窗口不在 harness 页面；全部不成立时不重新加载主窗口。
// 与 Go 版 internal/settings.Save 的判定一致（settings-window 主规格 settings-save）。
export function shouldOpenRemoteAfterSettingsSave(input: SettingsSaveInput): boolean {
  return (
    input.previous.status !== 'ok' ||
    input.previous.url !== input.clean ||
    input.trimmedInput !== input.clean ||
    !input.mainShowsHarness
  )
}
