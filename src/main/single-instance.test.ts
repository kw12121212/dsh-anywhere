import { describe, expect, it } from 'vitest'
import { SingleInstanceActivator, type ActivationWindow } from './single-instance'

// recorder 记录注入窗口操作的调用顺序，并可配置最小化与销毁状态。
function recorder(options: { minimised?: boolean; destroyed?: boolean } = {}) {
  const calls: string[] = []
  let minimised = options.minimised ?? false
  const window: ActivationWindow = {
    isDestroyed: () => options.destroyed ?? false,
    isMinimised: () => minimised,
    restore: () => {
      minimised = false
      calls.push('restore')
    },
    show: () => calls.push('show'),
    focus: () => calls.push('focus')
  }
  return { window, calls }
}

describe('SingleInstanceActivator', () => {
  it('窗口未就绪时收到的请求被挂起，start 后按顺序补做一次', () => {
    const { window, calls } = recorder()
    const activator = new SingleInstanceActivator()
    activator.bind(window)
    activator.request()
    activator.request()
    expect(calls).toEqual([])
    activator.start()
    expect(calls).toEqual(['show', 'focus'])
    // 挂起的请求只补做一次。
    expect(calls).toHaveLength(2)
  })

  it('窗口就绪后的请求立即按顺序激活', () => {
    const { window, calls } = recorder()
    const activator = new SingleInstanceActivator()
    activator.bind(window)
    activator.start()
    expect(calls).toEqual([])
    activator.request()
    expect(calls).toEqual(['show', 'focus'])
    activator.request()
    expect(calls).toEqual(['show', 'focus', 'show', 'focus'])
  })

  it('最小化窗口先取消最小化，再显示并聚焦', () => {
    const { window, calls } = recorder({ minimised: true })
    const activator = new SingleInstanceActivator()
    activator.bind(window)
    activator.start()
    activator.request()
    expect(calls).toEqual(['restore', 'show', 'focus'])
  })

  it('start 前没有挂起请求时，start 不触发激活', () => {
    const { window, calls } = recorder()
    const activator = new SingleInstanceActivator()
    activator.bind(window)
    activator.start()
    expect(calls).toEqual([])
  })

  it('窗口已销毁时激活不做任何事（副作用不得触达已销毁窗口）', () => {
    const { window, calls } = recorder({ destroyed: true })
    const activator = new SingleInstanceActivator()
    activator.bind(window)
    activator.start()
    activator.request()
    expect(calls).toEqual([])
  })
})
