import { describe, expect, it } from 'vitest'
import { isRemotePermissionAllowed } from './permissions'

describe('isRemotePermissionAllowed', () => {
  it('只放行白名单中的权限', () => {
    expect(isRemotePermissionAllowed('clipboard-sanitized-write')).toBe(true)
    expect(isRemotePermissionAllowed('fullscreen')).toBe(true)
  })

  it('其他权限一律拒绝', () => {
    for (const permission of ['notifications', 'media', 'geolocation', 'clipboard-read', 'openExternal', 'unknown']) {
      expect(isRemotePermissionAllowed(permission)).toBe(false)
    }
  })
})
