// mock-dsh-server --manage 的 DeepSeek 服务（dsbot-deepseek-services 模拟器验收）：
// 按 ds-bot 0.3.10 src/host/deepseek-services.js 源码核对的形态实现 deepseek-services、
// deepseek-services-config（ocr/tts/voice 与 null 恢复默认）与 deepseek-synthesize（内存生成有效 WAV base64）。
// 调用记录只记端点与参数键，不记正文与音频数据。
const OFFICIAL_ORIGIN = 'https://api.deepseek.com'
const DEFAULT_REF = 'DEEPSEEK_API_KEY'
const ASR_PROVIDER_ID = 'deepseek-asr'
const VOICES = ['stella', 'tide']
const TTS_BILLED_MAX = 10_000
const TTS_BODY_MAX = 60 * 1024

const invalid = (message) => Object.assign(new Error(message), { code: 'bot/invalid' })
const unavailable = (message) => Object.assign(new Error(message), { code: 'bot/unavailable' })
const readOnlyErr = (message) => Object.assign(new Error(message), { code: 'bot/read-only' })

const billedLength = (text) => [...text].reduce((total, ch) => total + (/\p{Script=Han}/u.test(ch) ? 2 : 1), 0)

/** 在内存中生成一段短 PCM WAV（16kHz 单声道 16-bit 440Hz 柔音，约 0.3 秒），供手机端 MediaPlayer 纯内存试听。 */
function makeWavBase64() {
  const sampleRate = 16000
  const samples = Math.floor(sampleRate * 0.3)
  const dataBytes = samples * 2
  const buf = Buffer.alloc(44 + dataBytes)
  buf.write('RIFF', 0, 'ascii')
  buf.writeUInt32LE(36 + dataBytes, 4)
  buf.write('WAVE', 8, 'ascii')
  buf.write('fmt ', 12, 'ascii')
  buf.writeUInt32LE(16, 16)
  buf.writeUInt16LE(1, 20)
  buf.writeUInt16LE(1, 22)
  buf.writeUInt32LE(sampleRate, 24)
  buf.writeUInt32LE(sampleRate * 2, 28)
  buf.writeUInt16LE(2, 32)
  buf.writeUInt16LE(16, 34)
  buf.write('data', 36, 'ascii')
  buf.writeUInt32LE(dataBytes, 40)
  for (let i = 0; i < samples; i += 1) {
    const env = Math.min(1, i / 400, (samples - i) / 800)
    const val = Math.round(Math.sin((2 * Math.PI * 440 * i) / sampleRate) * 6000 * env)
    buf.writeInt16LE(val, 44 + i * 2)
  }
  return buf.toString('base64')
}

const SAMPLE_WAV_B64 = makeWavBase64()

export function createDeepSeekServices({ bump, isDeepSeekKeyConfigured }) {
  let enabled = true
  let readOnly = false
  let keyOverride = null // null = 跟随 providers.deepseek；true/false = 强制覆盖
  let keyReason = null
  let speechRegistered = false
  let speechReason = 'This DSH has no speech-to-text service (voice input is not mounted)'
  let prefs = {} // 仅保存非默认覆盖，与上游 normalizeServices 一致
  const calls = []

  function reset() {
    enabled = true
    readOnly = false
    keyOverride = null
    keyReason = null
    speechRegistered = false
    speechReason = 'This DSH has no speech-to-text service (voice input is not mounted)'
    prefs = {}
  }

  function effectivePrefs() {
    return {
      ocr: prefs.ocr === true,
      tts: prefs.tts === 'deepseek' ? 'deepseek' : 'system',
      voice: VOICES.includes(prefs.voice) ? prefs.voice : 'stella',
    }
  }

  function keyConfigured() {
    if (keyOverride !== null) return keyOverride
    return typeof isDeepSeekKeyConfigured === 'function' ? Boolean(isDeepSeekKeyConfigured()) : true
  }

  function status() {
    const configured = keyConfigured()
    const missingReason = configured ? undefined : (keyReason || `The DeepSeek API key (${DEFAULT_REF}) is not set`)
    const p = effectivePrefs()
    const keyObj = { ref: DEFAULT_REF, configured, ...(keyReason && !configured ? { reason: keyReason } : {}) }
    return {
      endpoint: OFFICIAL_ORIGIN,
      key: keyObj,
      ocr: {
        supported: true,
        enabled: p.ocr,
        configured,
        ...(missingReason ? { reason: missingReason } : {}),
      },
      tts: {
        supported: true,
        enabled: p.tts === 'deepseek',
        configured,
        voices: [...VOICES],
        ...(missingReason ? { reason: missingReason } : {}),
      },
      speech: {
        registered: speechRegistered,
        providerId: ASR_PROVIDER_ID,
        configured,
        ready: speechRegistered && configured,
        ...(!speechRegistered
          ? { reason: speechReason }
          : (!configured ? { reason: missingReason } : {})),
      },
      preferences: p,
    }
  }

  const endpoints = {
    'deepseek-services'() {
      return status()
    },
    'deepseek-services-config'(body) {
      if (readOnly) throw readOnlyErr('This host is read-only')
      if (body === null || typeof body !== 'object' || Array.isArray(body)) {
        throw invalid('DeepSeek services config must be an object')
      }
      const next = { ...prefs }
      if (Object.hasOwn(body, 'ocr')) {
        if (body.ocr === null) delete next.ocr
        else if (body.ocr === true || body.ocr === false) next.ocr = body.ocr
        else throw invalid('ocr must be true, false or null')
      }
      if (Object.hasOwn(body, 'tts')) {
        if (body.tts === null) delete next.tts
        else if (body.tts === 'deepseek' || body.tts === 'system') next.tts = body.tts
        else throw invalid('tts must be "deepseek", "system" or null')
      }
      if (Object.hasOwn(body, 'voice')) {
        if (body.voice === null) delete next.voice
        else if (VOICES.includes(body.voice)) next.voice = body.voice
        else throw invalid(`voice must be ${VOICES.join(' or ')} or null`)
      }
      const cleaned = {}
      if (next.ocr === true) cleaned.ocr = true
      if (next.tts === 'deepseek' || next.tts === 'system') cleaned.tts = next.tts
      if (VOICES.includes(next.voice)) cleaned.voice = next.voice
      prefs = cleaned
      if (typeof bump === 'function') bump()
      return status()
    },
    'deepseek-synthesize'(body) {
      const p = effectivePrefs()
      if (p.tts !== 'deepseek') throw unavailable('DeepSeek text-to-speech is off. Turn it on in DS Bot settings.')
      const text = typeof body?.text === 'string' ? body.text : ''
      if (text.trim() === '') throw invalid('Give the text to read')
      const voice = body?.voice === undefined ? p.voice : String(body.voice)
      if (!VOICES.includes(voice)) throw invalid(`Voice must be one of ${VOICES.join(', ')}`)
      const billed = billedLength(text)
      if (billed > TTS_BILLED_MAX) {
        throw invalid(`The text is too long to read in one go (${billed} billed characters; at most ${TTS_BILLED_MAX}). Split it into shorter parts.`)
      }
      if (Buffer.byteLength(JSON.stringify({ text, voice_id: voice, format: 'wav' })) > TTS_BODY_MAX) {
        throw invalid('The text is too long to send in one request')
      }
      if (!keyConfigured()) {
        throw unavailable(keyReason || `The DeepSeek API key (${DEFAULT_REF}) is not set. Add it under Settings → Models; these services bill that key directly on api.deepseek.com.`)
      }
      return { audioBase64: SAMPLE_WAV_B64, mediaType: 'audio/wav' }
    },
  }

  function handle(endpoint, p, ok, fail) {
    const fn = endpoints[endpoint]
    if (!fn) return null
    calls.push({ endpoint, keys: Object.keys(p ?? {}).sort(), at: Date.now() })
    if (!enabled) {
      return { ok: false, error: { code: 'bot/not-found', message: `Unknown endpoint ${endpoint}`, details: {} } }
    }
    try {
      return ok(fn(p ?? {}))
    } catch (error) {
      return error.code
        ? { ok: false, error: { code: error.code, message: error.message, details: {} } }
        : fail(error.message)
    }
  }

  // /mock/deepseek-services：?enabled=0|1&key=0|1|auto&keyReason=...&speechReady=0|1&readOnly=0|1&reset=1&resetCalls=1
  function set(params) {
    if (params.get('reset') === '1') reset()
    const en = params.get('enabled')
    if (en === '0' || en === '1') enabled = en === '1'
    const ro = params.get('readOnly')
    if (ro === '0' || ro === '1') readOnly = ro === '1'
    const k = params.get('key')
    if (k === '0') keyOverride = false
    else if (k === '1') keyOverride = true
    else if (k === 'auto') keyOverride = null
    const kr = params.get('keyReason')
    if (kr !== null) keyReason = kr === '' ? null : kr
    const sr = params.get('speechReady')
    if (sr === '0' || sr === '1') speechRegistered = sr === '1'
    if (params.get('resetCalls') === '1') calls.length = 0
    return { enabled, readOnly, status: status(), calls }
  }

  return { handle, set, status }
}
