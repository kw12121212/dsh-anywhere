import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  workers: 1,
  // CI 共享 runner 上剪贴板、窗口时序等环境性抖动会偶发挂测试（两次 CI 分别挂在
  // 不同的测试上），仅在 CI 重试；本地保持 0 重试，不掩盖真实的确定性失败。
  retries: process.env.CI ? 2 : 0,
  reporter: 'list'
})
