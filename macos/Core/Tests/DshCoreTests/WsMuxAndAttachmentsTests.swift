import Foundation
import XCTest
@testable import DshCore

final class WsMuxAndAttachmentsTests: XCTestCase {
    func testWsFrameCodecRoundTripMaskingAndLengths() throws {
        // 7-bit payload
        let small = WsFrameCodec.encodeText("你好 ws", mask: [1, 2, 3, 4])
        var buf = small
        let decoded = try WsFrameCodec.decodeFrames(from: &buf)
        XCTAssertTrue(buf.isEmpty)
        XCTAssertEqual([WsFrameCodec.Frame(fin: true, opcode: .text, payload: Data("你好 ws".utf8), masked: true)], decoded)

        // 16-bit payload (>= 126)
        let medText = String(repeating: "a", count: 300)
        var medBuf = WsFrameCodec.encodeText(medText, mask: nil)
        let medDecoded = try WsFrameCodec.decodeFrames(from: &medBuf)
        XCTAssertEqual(medText, String(data: medDecoded[0].payload, encoding: .utf8))

        // 64-bit payload (>= 65536)
        let largeData = Data(repeating: 0x41, count: 70_000)
        var largeBuf = WsFrameCodec.encodeFrame(fin: true, opcode: .text, payload: largeData, mask: [9, 8, 7, 6])
        let largeDecoded = try WsFrameCodec.decodeFrames(from: &largeBuf)
        XCTAssertEqual(70_000, largeDecoded[0].payload.count)

        // 半包等待 + 增量续喂
        var splitBuf = Data(small.prefix(3))
        XCTAssertTrue(try WsFrameCodec.decodeFrames(from: &splitBuf).isEmpty)
        splitBuf.append(small.dropFirst(3))
        XCTAssertEqual(1, try WsFrameCodec.decodeFrames(from: &splitBuf).count)
    }

    func testWsMessageAssemblerFragmentationAndErrors() throws {
        let assembler = WsMessageAssembler()
        let raw = Data("分段消息整体".utf8)
        let mid = raw.count / 2
        let f1 = WsFrameCodec.Frame(fin: false, opcode: .text, payload: Data(raw.prefix(mid)))
        let f2 = WsFrameCodec.Frame(fin: true, opcode: .continuation, payload: Data(raw.dropFirst(mid)))
        XCTAssertNil(try assembler.push(f1))
        XCTAssertEqual(.text("分段消息整体"), try assembler.push(f2))

        XCTAssertEqual(.ping(Data("p".utf8)), try assembler.push(.init(fin: true, opcode: .ping, payload: Data("p".utf8))))
        XCTAssertEqual(.close(Data()), try assembler.push(.init(fin: true, opcode: .close, payload: Data())))

        XCTAssertThrowsError(try WsMessageAssembler().push(.init(fin: true, opcode: .continuation, payload: Data()))) { err in
            XCTAssertEqual(WsFrameCodec.CodecError.continuationWithoutStart, err as? WsFrameCodec.CodecError)
        }
        XCTAssertThrowsError(try WsMessageAssembler().push(.init(fin: true, opcode: .binary, payload: Data()))) { err in
            XCTAssertEqual(WsFrameCodec.CodecError.unexpectedBinaryFrame, err as? WsFrameCodec.CodecError)
        }

        // frameTooLarge
        var oversize = Data([0x81, 127, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
        XCTAssertThrowsError(try WsFrameCodec.decodeFrames(from: &oversize))

        // 非 4 字节 mask 应降级为无掩码帧而不是生成畸形帧头
        var badMaskBuf = WsFrameCodec.encodeFrame(fin: true, opcode: .text, payload: Data("ok".utf8), mask: [1, 2])
        let badMaskDecoded = try WsFrameCodec.decodeFrames(from: &badMaskBuf)
        XCTAssertEqual([WsFrameCodec.Frame(fin: true, opcode: .text, payload: Data("ok".utf8), masked: false)], badMaskDecoded)
    }

    func testWsHandshakeValidationAndRfc6455Vector() throws {
        // RFC 6455 Section 4.2.2 标准测试向量
        XCTAssertEqual("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrameCodec.acceptKey(for: "dGhlIHNhbXBsZSBub25jZQ=="))

        let req = WsFrameCodec.buildHandshakeRequest(
            path: "/api/remote.mux",
            hostHeader: "h.test",
            clientKey: "dGhlIHNhbXBsZSBub25jZQ==",
            headers: [("Cookie", "dsh-auth-1=v1")]
        )
        XCTAssertTrue(req.contains("GET /api/remote.mux HTTP/1.1\r\n"))
        XCTAssertTrue(req.contains("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"))

        let okHead = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        XCTAssertNoThrow(try WsFrameCodec.validateHandshakeResponse(okHead, clientKey: "dGhlIHNhbXBsZSBub25jZQ=="))

        let badAccept = "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: wrong==\r\n\r\n"
        XCTAssertThrowsError(try WsFrameCodec.validateHandshakeResponse(badAccept, clientKey: "dGhlIHNhbXBsZSBub25jZQ==")) { err in
            XCTAssertEqual(WsFrameCodec.CodecError.acceptMismatch, err as? WsFrameCodec.CodecError)
        }

        let reject401 = "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n"
        XCTAssertThrowsError(try WsFrameCodec.validateHandshakeResponse(reject401, clientKey: "dGhlIHNhbXBsZSBub25jZQ==")) { err in
            XCTAssertEqual(WsFrameCodec.CodecError.handshakeRejected(status: 401, bodyText: reject401), err as? WsFrameCodec.CodecError)
        }
    }

    func testDshMuxCallItemEndAndError() {
        final class FakeSession: MuxTextSession {
            var sent: [String] = []
            var queue: [String?]
            var closed = false
            init(_ replies: [String?]) { self.queue = replies }
            func sendText(_ text: String) throws { sent.append(text) }
            func receiveText() throws -> String? { queue.isEmpty ? nil : queue.removeFirst() }
            func close() { closed = true }
        }

        let s1 = FakeSession([
            #"{"type":"item","streamId":"other","value":{"ignored":true}}"#,
            #"{"type":"item","streamId":"a1","value":{"echo":"session/attachment","n":1}}"#,
            #"{"type":"item","streamId":"a1","value":{"echo":"session/attachment","n":2}}"#,
            #"{"type":"end","streamId":"a1"}"#,
        ])
        let mux1 = DshMux(session: s1)
        let res1 = mux1.call(endpoint: "session/attachment", requestJson: #"{"sessionId":"s1","attachment":{}}"#)
        XCTAssertEqual(.ok(#"{"echo":"session/attachment","n":2}"#), res1)
        XCTAssertTrue(s1.sent[0].contains(#""type":"open""#))
        XCTAssertTrue(s1.sent[0].contains(#""payload":{"args":{"request":"#))
        mux1.close()
        XCTAssertTrue(s1.closed)

        let s2 = FakeSession([#"{"type":"error","streamId":"a1","error":{"code":"gateway/input-invalid","message":"bad"}}"#])
        let mux2 = DshMux(session: s2)
        XCTAssertEqual(.err(code: "gateway/input-invalid", message: "bad"), mux2.call(endpoint: "session/prompt", requestJson: "{}"))

        XCTAssertEqual(#"{"type":"cancel","streamId":"a1"}"#, DshMux.encodeCancel(streamId: "a1"))
        let s3 = FakeSession(["not json"])
        XCTAssertEqual(.err(code: "gateway/protocol", message: "non-object frame on mux"), DshMux(session: s3).call(endpoint: "x", requestJson: "{}"))
        let s4 = FakeSession([nil])
        XCTAssertEqual(.err(code: "gateway/closed", message: "websocket closed before reply"), DshMux(session: s4).call(endpoint: "x", requestJson: "{}"))
    }

    func testAttachmentMessages() throws {
        let uploadJson = #"{"receiptId":"r1","file":{"attachmentId":"sha256:abc","name":"图.png","bytes":15,"width":2,"height":3,"mediaType":"image/png"}}"#
        let decoded = try XCTUnwrap(AttachmentMessages.decodeUpload(try XCTUnwrap(Json.parse(uploadJson))))
        XCTAssertEqual("r1", decoded.receiptId)
        XCTAssertEqual("sha256:abc", decoded.attachmentId)
        XCTAssertEqual("图.png", decoded.name)
        XCTAssertEqual(15, decoded.bytes)

        let imageUpload = try XCTUnwrap(Json.parse(#"{"receiptId":"r9","image":{"attachmentId":"sha256:aa","mediaType":"image/png","width":1,"height":1,"bytes":70,"name":"t.png"}}"#))
        let decodedImg = try XCTUnwrap(AttachmentMessages.decodeUpload(imageUpload))
        XCTAssertEqual("sha256:aa", decodedImg.attachmentId)
        XCTAssertEqual("t.png", decodedImg.name)

        XCTAssertEqual(
            #"{"type":"file","receiptId":"r1"},{"type":"file","receiptId":"r2"},{"type":"text","text":"看图"}"#,
            AttachmentMessages.promptContent(receiptIds: ["r1", "r2"], text: "看图")
        )
        XCTAssertEqual(#"{"type":"file","receiptId":"r1"}"#, AttachmentMessages.promptContent(receiptIds: ["r1"], text: ""))
        XCTAssertEqual("", AttachmentMessages.promptContent(receiptIds: [], text: ""))

        let req = AttachmentMessages.clientRequest(rpcId: "r-1", method: "session/prompt", requestJson: #"{"sessionId":"s1"}"#)
        let parsedReq = try XCTUnwrap(Json.parse(req)?.objectValue)
        XCTAssertEqual("client-request", parsedReq.string("type"))
        XCTAssertEqual("r-1", parsedReq.string("rpcId"))

        let okResp = AttachmentMessages.parseServerResponse(#"{"type":"server-response","rpcId":"r-1","result":{"ok":true,"value":{"accepted":true}}}"#)
        XCTAssertEqual(.ok(#"{"accepted":true}"#), okResp)
        let errResp = AttachmentMessages.parseServerResponse(#"{"type":"server-response","rpcId":"r-1","result":{"ok":false,"error":{"code":"session/not-found","message":"no"}}}"#)
        XCTAssertEqual(.err(code: "session/not-found", message: "no"), errResp)
        XCTAssertNil(AttachmentMessages.parseServerResponse(#"{"ok":true,"value":1}"#))
    }
}
