// 应用显示名称与“关于”文案的唯一来源（对应 Go 版 internal/appinfo，供菜单、
// 窗口标题与关于对话框共用，不得在其他模块重复身份字面量）。
export const DISPLAY_NAME = 'DSH Anywhere'

// ABOUT_TITLE 是“关于”菜单项与对话框的标题。
export const ABOUT_TITLE = `关于 ${DISPLAY_NAME}`

// aboutMessage 是“关于”对话框的正文，显示应用名称与版本号。
export function aboutMessage(version: string): string {
  return `${DISPLAY_NAME}\n版本 ${version}`
}
