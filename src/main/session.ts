import { hasToken } from './config'

// SESSION_PARTITION 是远程视图使用的持久化分区，会话 cookie 跨应用重启保留。
export const SESSION_PARTITION = 'persist:dsh'

// EXPIRED_MARKER 是 dsh web 在会话失效时以 401 text/plain 返回的固定正文。
export const EXPIRED_MARKER = 'dsh web authentication required; reopen the URL printed by dsh web.'

// READ_DOCUMENT_SCRIPT 在隔离世界中一次性读取文档类型与正文，返回值直接交给主进程。
export const READ_DOCUMENT_SCRIPT = '({ contentType: document.contentType, text: document.body ? document.body.textContent : null })'

export interface DocumentSnapshot {
  contentType: string
  text: string | null
}

// isSessionExpired 判断主框架响应是否是 dsh 的会话失效页：401、text/plain 且正文为固定文本。
export function isSessionExpired(status: number, doc: DocumentSnapshot | null): boolean {
  return status === 401 && doc !== null && doc.contentType === 'text/plain' && doc.text?.trim() === EXPIRED_MARKER
}

// tokenEntryIndices 返回导航历史中含 token 且不是当前条目的索引，按从大到小排列以便逐个删除。
export function tokenEntryIndices(urls: readonly string[], activeIndex: number): number[] {
  const indices: number[] = []
  urls.forEach((url, index) => {
    if (index !== activeIndex && hasToken(url)) {
      indices.push(index)
    }
  })
  return indices.reverse()
}
