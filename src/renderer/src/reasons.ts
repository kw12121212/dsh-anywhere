import type { FailureReason } from '../../main/probe'

// 加载失败原因在错误页上的说明文字，与 Go 版一致。
export const REASON_TEXT: Record<FailureReason, string> = {
  dns: '无法解析主机名，请检查地址是否正确以及网络（如 Tailscale）是否已连接。',
  refused: '连接被拒绝：主机上的 dsh web 可能没有运行，或端口不正确。',
  timeout: '连接超时：主机可能已关机或网络不可达。',
  tls: '安全连接（TLS）失败：证书不受信任或与主机名不符。',
  other: '无法连接到该地址。'
}
