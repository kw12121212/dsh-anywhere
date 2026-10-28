import type { WebContents } from 'electron'
import { classifyOpen } from './navigation-policy'
import { classifyNetError } from './probe'
import type { FailureReason } from './probe'
import { READ_DOCUMENT_SCRIPT, isSessionExpired, tokenEntryIndices } from './session'
import type { DocumentSnapshot } from './session'

// 读取会话失效页正文所用的隔离世界 ID；与页面脚本不共享全局对象。
const READ_WORLD_ID = 1001

// Chromium 的 ERR_ABORTED：导航被新导航取代或被 preventDefault，不是加载失败。
const ERR_ABORTED = -3

export interface RemoteViewDeps {
  harnessURL(): string | null
  openExternal(url: string): void
  // 当前导航代次，会话失效判定开始读取正文时记下，完成后随结果交回以丢弃过时的判定。
  generation(): number
  sessionExpired(observedGeneration: number): void
  loadFailed(reason: FailureReason): void
}

// attachRemoteView 在加载任何远程地址之前为远程视图注册导航范围、会话失效与历史清理处理
// （技术标准 electron-layout-security/remote-view-navigation-policy）。
export function attachRemoteView(contents: WebContents, deps: RemoteViewDeps): void {
  // 只对已保存地址同源的地址放行，其余交给系统浏览器或忽略。
  const route = (url: string): 'stay' | 'handled' => {
    const harness = deps.harnessURL()
    const action = harness === null ? 'ignore' : classifyOpen(harness, url)
    if (action === 'external') {
      deps.openExternal(url)
    }
    return action === 'stay' ? 'stay' : 'handled'
  }

  contents.on('will-navigate', (event) => {
    if (route(event.url) === 'handled') {
      event.preventDefault()
    }
  })

  contents.setWindowOpenHandler(({ url }) => {
    if (route(url) === 'stay') {
      contents.loadURL(url).catch(() => {
        // 失败由 did-fail-load 处理。
      })
    }
    return { action: 'deny' }
  })

  let lastStatus = 0
  contents.on('did-navigate', (_event, _url, httpResponseCode) => {
    lastStatus = httpResponseCode
    removeTokenEntries(contents)
  })
  contents.on('did-navigate-in-page', (_event, _url, isMainFrame) => {
    if (isMainFrame) {
      removeTokenEntries(contents)
    }
  })

  contents.on('did-finish-load', () => {
    if (lastStatus !== 401) {
      return
    }
    const observed = deps.generation()
    contents
      .executeJavaScriptInIsolatedWorld(READ_WORLD_ID, [{ code: READ_DOCUMENT_SCRIPT }])
      .then((doc: unknown) => {
        if (isSessionExpired(lastStatus, doc as DocumentSnapshot | null)) {
          deps.sessionExpired(observed)
        }
      })
      .catch(() => {
        // 读取失败时保持远程页面原样显示。
      })
  })

  contents.on('did-fail-load', (_event, errorCode, errorDescription, _validatedURL, isMainFrame) => {
    if (isMainFrame && errorCode !== ERR_ABORTED) {
      deps.loadFailed(classifyNetError(errorDescription))
    }
  })
}

// 带 token 的地址只用于换取会话；会话建立后从导航历史中删除这些条目。
function removeTokenEntries(contents: WebContents): void {
  const history = contents.navigationHistory
  const urls = history.getAllEntries().map((entry) => entry.url)
  for (const index of tokenEntryIndices(urls, history.getActiveIndex())) {
    history.removeEntryAtIndex(index)
  }
}
