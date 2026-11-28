import DshCore
import Foundation
import ImageIO
import Observation
import UniformTypeIdentifiers

/// 待发送附件（attachments-send）：Idle（待上传）/ Uploading / Ready（receipt 齐备）/
/// Failed（原因，可单件重试）。字节只在内存，不落盘不进日志。
struct PendingAttachment: Identifiable, Equatable {
    enum State: Equatable {
        case idle
        case uploading
        case ready(receiptId: String, attachmentId: String)
        case failed(reason: String)
    }

    let id: UUID
    var name: String
    var mediaType: String?
    var data: Data
    var isImage: Bool
    var state: State

    init(name: String, mediaType: String?, data: Data) {
        self.id = UUID()
        self.name = name
        self.mediaType = mediaType
        self.data = data
        let lower = name.lowercased()
        let imageExts: Set<String> = ["png", "jpg", "jpeg", "gif", "heic", "webp", "tiff", "bmp"]
        self.isImage = mediaType?.hasPrefix("image/") == true
            || imageExts.contains((lower as NSString).pathExtension)
        self.state = .idle
    }
}

/// 图片压缩（photo-compress）：长边 > 2048 等比缩小 + JPEG 0.82 重编码，EXIF 方向按拍摄
/// 方向校正后输出（名字与 mediaType 换成 .jpg/image/jpeg）；GIF（动图）与 PNG 截图原样返回。
enum ImageCompressor {
    /// 返回 (名字, mediaType, 字节)；不需要压缩时原样返回。
    static func process(name: String, mediaType: String?, data: Data) -> (name: String, mediaType: String?, data: Data) {
        let lower = name.lowercased()
        if lower.hasSuffix(".gif") || mediaType == "image/gif" { return (name, mediaType, data) }
        if lower.hasSuffix(".png") || mediaType == "image/png" { return (name, mediaType, data) }
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(src) == 1,
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int,
              let h = props[kCGImagePropertyPixelHeight] as? Int,
              ImageSizing.target(width: w, height: h) != nil,
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil)
        else { return (name, mediaType, data) }

        let orientation = props[kCGImagePropertyOrientation] as? UInt32 ?? 1
        let oriented = orientedImage(img, exif: orientation) ?? img
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else {
            return (name, mediaType, data)
        }
        let opts: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: ImageSizing.jpegQuality]
        CGImageDestinationAddImage(dest, oriented, opts as CFDictionary)
        guard CGImageDestinationFinalize(dest), out.length < data.count else { return (name, mediaType, data) }
        let base = (name as NSString).deletingPathExtension
        let newName = ((base.isEmpty ? "image" : base) as NSString).appendingPathExtension("jpg") ?? "image.jpg"
        return (newName, "image/jpeg", out as Data)
    }

    /// 按 EXIF 方向绘制到新位图（1=up 无需变换返回 nil）。
    private static func orientedImage(_ img: CGImage, exif: UInt32) -> CGImage? {
        guard exif != 1 else { return nil }
        let w = img.width
        let h = img.height
        let swapped = exif >= 5
        let outW = swapped ? h : w
        let outH = swapped ? w : h
        guard let ctx = CGContext(
            data: nil, width: outW, height: outH,
            bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        ctx.translateBy(x: CGFloat(outW) / 2, y: CGFloat(outH) / 2)
        switch exif {
        case 2: ctx.scaleBy(x: -1, y: 1)
        case 3: ctx.rotate(by: .pi)
        case 4: ctx.scaleBy(x: 1, y: -1)
        case 5: ctx.rotate(by: .pi / 2); ctx.scaleBy(x: -1, y: 1)
        case 6: ctx.rotate(by: -.pi / 2)
        case 7: ctx.rotate(by: .pi / 2)
        case 8: ctx.rotate(by: .pi / 2); ctx.scaleBy(x: 1, y: -1)
        default: break
        }
        ctx.draw(img, in: CGRect(x: -CGFloat(w) / 2, y: -CGFloat(h) / 2, width: CGFloat(w), height: CGFloat(h)))
        return ctx.makeImage()
    }
}

/// 附件装配（App 层注入控制器）。session 族 RPC（session/prompt、session/attachment、
/// file-text、file-download）一律走 HTTP unary POST /api/<channel>/<method>，请求体为
/// client-request 信封、应答经 parseServerResponse 解析（tech/dsbot-api FINDINGS 实测形态；
/// websocket mux 路径保留为已测组件不使用）。上传为 uploadFileBinary octet-stream 直传。
enum AttachmentAssembly {
    /// 通用 HTTP unary 调用：POST /api/<endpoint 的通道/方法>，client-request 信封包体。
    /// endpoint 形如 "session/prompt"。
    static func unaryPost(base: String, endpoint: String, requestJson: String, cookieHeader: String?) async throws -> String {
        var b = base.trimmingCharacters(in: .whitespaces)
        if !b.hasSuffix("/") { b += "/" }
        guard let url = URL(string: b + "api/" + endpoint) else { throw TransportError(.other) }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        let rpcId = "rpc-\(UUID().uuidString)"
        let body = AttachmentMessages.clientRequest(rpcId: rpcId, method: endpoint, requestJson: requestJson)
        req.httpBody = Data(body.utf8)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let cookieHeader {
            req.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
        }
        let config = makeEphemeralConfiguration(requestTimeout: 30, resourceTimeout: 60)
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw TransportError(.other)
        }
        return String(decoding: data, as: UTF8.self)
    }

    /// 上传执行器：base/cookie 由控制器在调用时传参（不闭包捕获陈旧值）。
    static func makeUploader() -> MacosSessionController.AttachmentUploader {
        { sessionId, name, mediaType, data, context in
            guard let context, let url = URL(string: AttachmentUpload.urlQuery(baseUrl: context.base, sessionId: sessionId, name: name, mediaType: mediaType)) else {
                throw TransportError(.other)
            }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            if let cookie = context.cookieHeader {
                req.setValue(cookie, forHTTPHeaderField: "Cookie")
            }
            let config = makeEphemeralConfiguration(requestTimeout: 60, resourceTimeout: 120)
            let session = URLSession(configuration: config)
            defer { session.finishTasksAndInvalidate() }
            let (bytes, resp) = try await session.upload(for: req, from: data)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
                throw TransportError(.other)
            }
            return String(decoding: bytes, as: UTF8.self)
        }
    }

    /// session 族 RPC 调用器（HTTP unary）。
    static func makeMuxCaller() -> MacosSessionController.MuxCaller {
        { endpoint, requestJson, context in
            guard let context else { throw TransportError(.other) }
            return try await unaryPost(base: context.base, endpoint: endpoint, requestJson: requestJson, cookieHeader: context.cookieHeader)
        }
    }
}
