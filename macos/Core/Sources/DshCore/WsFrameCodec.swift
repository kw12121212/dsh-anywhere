import Foundation

/// 纯 Foundation RFC 6455 WebSocket 帧编解码与握手校验（不含网络 IO，与 `WsClient.kt` 帧格式对齐）。
public enum WsFrameCodec: Sendable {
    public static let maxFrameBytes = 64 * 1024 * 1024
    private static let wsGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    public enum Opcode: UInt8, Equatable, Sendable {
        case continuation = 0x0
        case text = 0x1
        case binary = 0x2
        case close = 0x8
        case ping = 0x9
        case pong = 0xA
    }

    public struct Frame: Equatable, Sendable {
        public let fin: Bool
        public let opcode: Opcode
        public let payload: Data
        public let masked: Bool

        public init(fin: Bool, opcode: Opcode, payload: Data, masked: Bool = false) {
            self.fin = fin
            self.opcode = opcode
            self.payload = payload
            self.masked = masked
        }
    }

    public enum CodecError: Error, Equatable, Sendable {
        case frameTooLarge(UInt64)
        case unknownOpcode(UInt8)
        case continuationWithoutStart
        case unexpectedBinaryFrame
        case invalidUtf8
        case handshakeRejected(status: Int, bodyText: String)
        case malformedHandshake(String)
        case acceptMismatch
    }

    public static func randomMask() -> [UInt8] {
        [
            UInt8.random(in: 0...255),
            UInt8.random(in: 0...255),
            UInt8.random(in: 0...255),
            UInt8.random(in: 0...255),
        ]
    }

    /// 编码单个 RFC 6455 帧；客户端发送传 `mask`（缺省生成随机 4 字节掩码）。
    public static func encodeFrame(
        fin: Bool = true,
        opcode: Opcode,
        payload: Data,
        mask: [UInt8]? = randomMask()
    ) -> Data {
        var out = Data()
        let firstByte: UInt8 = (fin ? 0x80 : 0x00) | opcode.rawValue
        out.append(firstByte)

        let validMask = mask.flatMap { $0.count == 4 ? $0 : nil }
        let len = payload.count
        let maskBit: UInt8 = validMask != nil ? 0x80 : 0x00
        if len < 126 {
            out.append(maskBit | UInt8(len))
        } else if len < 65536 {
            out.append(maskBit | 126)
            out.append(UInt8((len >> 8) & 0xFF))
            out.append(UInt8(len & 0xFF))
        } else {
            out.append(maskBit | 127)
            let len64 = UInt64(len)
            for shift in stride(from: 56, through: 0, by: -8) {
                out.append(UInt8((len64 >> shift) & 0xFF))
            }
        }

        if let validMask {
            out.append(contentsOf: validMask)
            var maskedPayload = Data(count: len)
            for (i, b) in payload.enumerated() {
                maskedPayload[i] = b ^ validMask[i & 3]
            }
            out.append(maskedPayload)
        } else {
            out.append(payload)
        }
        return out
    }

    public static func encodeText(_ text: String, fin: Bool = true, mask: [UInt8]? = randomMask()) -> Data {
        encodeFrame(fin: fin, opcode: .text, payload: Data(text.utf8), mask: mask)
    }

    public static func encodeControl(opcode: Opcode, payload: Data = Data(), mask: [UInt8]? = randomMask()) -> Data {
        encodeFrame(fin: true, opcode: opcode, payload: payload, mask: mask)
    }

    /// 从缓冲区尽可能解析完整帧；半帧留在 `buffer` 等待后续字节。
    public static func decodeFrames(
        from buffer: inout Data,
        maxFrameBytes: Int = WsFrameCodec.maxFrameBytes
    ) throws -> [Frame] {
        var frames: [Frame] = []
        var offset = buffer.startIndex

        while buffer.endIndex - offset >= 2 {
            let b0 = buffer[offset]
            let b1 = buffer[offset + 1]
            let fin = (b0 & 0x80) != 0
            let rawOp = b0 & 0x0F
            guard let opcode = Opcode(rawValue: rawOp) else {
                throw CodecError.unknownOpcode(rawOp)
            }
            let masked = (b1 & 0x80) != 0
            var cursor = offset + 2
            var payloadLen = UInt64(b1 & 0x7F)

            if payloadLen == 126 {
                guard buffer.endIndex - cursor >= 2 else { break }
                payloadLen = (UInt64(buffer[cursor]) << 8) | UInt64(buffer[cursor + 1])
                cursor += 2
            } else if payloadLen == 127 {
                guard buffer.endIndex - cursor >= 8 else { break }
                var acc: UInt64 = 0
                for i in 0..<8 {
                    acc = (acc << 8) | UInt64(buffer[cursor + i])
                }
                payloadLen = acc
                cursor += 8
            }

            if payloadLen > UInt64(maxFrameBytes) {
                throw CodecError.frameTooLarge(payloadLen)
            }
            let maskLen = masked ? 4 : 0
            let needed = maskLen + Int(payloadLen)
            guard buffer.endIndex - cursor >= needed else { break }

            let maskBytes: [UInt8]?
            if masked {
                maskBytes = Array(buffer[cursor..<(cursor + 4)])
                cursor += 4
            } else {
                maskBytes = nil
            }

            var payload = Data(buffer[cursor..<(cursor + Int(payloadLen))])
            if let maskBytes {
                for i in payload.indices {
                    let rel = payload.distance(from: payload.startIndex, to: i)
                    payload[i] ^= maskBytes[rel & 3]
                }
            }
            frames.append(Frame(fin: fin, opcode: opcode, payload: payload, masked: masked))
            offset = cursor + Int(payloadLen)
        }

        if offset > buffer.startIndex {
            buffer.removeSubrange(buffer.startIndex..<offset)
        }
        return frames
    }

    // MARK: - 握手请求构造与响应校验

    public static func randomClientKey() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        for i in bytes.indices {
            bytes[i] = UInt8.random(in: 0...255)
        }
        return Data(bytes).base64EncodedString()
    }

    public static func acceptKey(for clientKey: String) -> String {
        let digest = sha1(Array((clientKey + wsGuid).utf8))
        return Data(digest).base64EncodedString()
    }

    public static func buildHandshakeRequest(
        path: String,
        hostHeader: String,
        clientKey: String,
        headers: [(name: String, value: String)] = []
    ) -> String {
        var req = "GET \(path) HTTP/1.1\r\n"
        req += "Host: \(hostHeader)\r\n"
        req += "Upgrade: websocket\r\n"
        req += "Connection: Upgrade\r\n"
        req += "Sec-WebSocket-Key: \(clientKey)\r\n"
        req += "Sec-WebSocket-Version: 13\r\n"
        for (name, value) in headers {
            req += "\(name): \(value)\r\n"
        }
        req += "\r\n"
        return req
    }

    public static func validateHandshakeResponse(_ headText: String, clientKey: String) throws {
        let lines = headText.split(separator: "\r\n", omittingEmptySubsequences: false).map(String.init)
        guard let statusLine = lines.first, !statusLine.isEmpty else {
            throw CodecError.malformedHandshake("empty handshake response")
        }
        let statusParts = statusLine.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: true)
        guard statusParts.count >= 2,
              statusParts[0].hasPrefix("HTTP/1."),
              let status = Int(statusParts[1])
        else {
            throw CodecError.malformedHandshake(statusLine)
        }
        guard status == 101 else {
            throw CodecError.handshakeRejected(status: status, bodyText: headText)
        }
        let expected = acceptKey(for: clientKey)
        for line in lines.dropFirst() {
            if let colon = line.firstIndex(of: ":") {
                let name = String(line[..<colon]).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if name == "sec-websocket-accept" {
                    let actual = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                    if actual.caseInsensitiveCompare(expected) != .orderedSame {
                        throw CodecError.acceptMismatch
                    }
                    return
                }
            }
        }
    }

    /// 纯 Swift RFC 3174 SHA-1 实现（专用于 RFC 6455 `Sec-WebSocket-Accept` 握手校验）。
    public static func sha1(_ message: [UInt8]) -> [UInt8] {
        var msg = message
        let bitLen = UInt64(msg.count) * 8
        msg.append(0x80)
        while msg.count % 64 != 56 {
            msg.append(0)
        }
        for shift in stride(from: 56, through: 0, by: -8) {
            msg.append(UInt8((bitLen >> shift) & 0xFF))
        }

        var h0: UInt32 = 0x67452301
        var h1: UInt32 = 0xEFCDAB89
        var h2: UInt32 = 0x98BADCFE
        var h3: UInt32 = 0x10325476
        var h4: UInt32 = 0xC3D2E1F0

        var w = [UInt32](repeating: 0, count: 80)
        for chunkStart in stride(from: 0, to: msg.count, by: 64) {
            for i in 0..<16 {
                let j = chunkStart + i * 4
                w[i] = (UInt32(msg[j]) << 24) | (UInt32(msg[j + 1]) << 16) | (UInt32(msg[j + 2]) << 8) | UInt32(msg[j + 3])
            }
            for i in 16..<80 {
                let x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]
                w[i] = (x << 1) | (x >> 31)
            }

            var a = h0, b = h1, c = h2, d = h3, e = h4
            for i in 0..<80 {
                let f: UInt32
                let k: UInt32
                switch i {
                case 0..<20:
                    f = (b & c) | ((~b) & d)
                    k = 0x5A827999
                case 20..<40:
                    f = b ^ c ^ d
                    k = 0x6ED9EBA1
                case 40..<60:
                    f = (b & c) | (b & d) | (c & d)
                    k = 0x8F1BBCDC
                default:
                    f = b ^ c ^ d
                    k = 0xCA62C1D6
                }
                let temp = ((a << 5) | (a >> 27)) &+ f &+ e &+ k &+ w[i]
                e = d
                d = c
                c = (b << 30) | (b >> 2)
                b = a
                a = temp
            }
            h0 = h0 &+ a
            h1 = h1 &+ b
            h2 = h2 &+ c
            h3 = h3 &+ d
            h4 = h4 &+ e
        }

        var digest = [UInt8](repeating: 0, count: 20)
        for (idx, h) in [h0, h1, h2, h3, h4].enumerated() {
            digest[idx * 4] = UInt8((h >> 24) & 0xFF)
            digest[idx * 4 + 1] = UInt8((h >> 16) & 0xFF)
            digest[idx * 4 + 2] = UInt8((h >> 8) & 0xFF)
            digest[idx * 4 + 3] = UInt8(h & 0xFF)
        }
        return digest
    }
}

/// RFC 6455 分片文本消息重组器（处理 text + continuation 分片、ping/pong/close，拒绝孤立 continuation 与 binary）。
public final class WsMessageAssembler {
    public enum Event: Equatable, Sendable {
        case text(String)
        case ping(Data)
        case pong(Data)
        case close(Data)
    }

    private var assembled = Data()
    private var started = false

    public init() {}

    public func push(_ frame: WsFrameCodec.Frame) throws -> Event? {
        switch frame.opcode {
        case .text:
            assembled = frame.payload
            started = true
            if frame.fin {
                started = false
                let data = assembled
                assembled = Data()
                guard let text = String(data: data, encoding: .utf8) else {
                    throw WsFrameCodec.CodecError.invalidUtf8
                }
                return .text(text)
            }
            return nil
        case .continuation:
            guard started else {
                throw WsFrameCodec.CodecError.continuationWithoutStart
            }
            assembled.append(frame.payload)
            if frame.fin {
                started = false
                let data = assembled
                assembled = Data()
                guard let text = String(data: data, encoding: .utf8) else {
                    throw WsFrameCodec.CodecError.invalidUtf8
                }
                return .text(text)
            }
            return nil
        case .binary:
            throw WsFrameCodec.CodecError.unexpectedBinaryFrame
        case .ping:
            return .ping(frame.payload)
        case .pong:
            return .pong(frame.payload)
        case .close:
            return .close(frame.payload)
        }
    }
}
