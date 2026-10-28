// 远程视图导航策略（技术标准 electron-layout-security/remote-view-navigation-policy）。

export type OpenAction = 'stay' | 'external' | 'ignore'

// sameOrigin 判断两个地址的协议、主机与端口（含默认端口）是否都相同。
export function sameOrigin(a: string, b: string): boolean {
  const left = parseWebURL(a)
  const right = parseWebURL(b)
  return left !== null && right !== null && left.origin === right.origin
}

// classifyOpen 决定远程页面发起的导航或打开请求如何处理：
// 与已保存 harness 地址同源时留在主窗口，非同源 http/https 交给系统浏览器，其他协议忽略。
export function classifyOpen(harnessURL: string, target: string): OpenAction {
  if (parseWebURL(target) === null) {
    return 'ignore'
  }
  return sameOrigin(harnessURL, target) ? 'stay' : 'external'
}

function parseWebURL(raw: string): URL | null {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return null
  }
  return url.protocol === 'http:' || url.protocol === 'https:' ? url : null
}
