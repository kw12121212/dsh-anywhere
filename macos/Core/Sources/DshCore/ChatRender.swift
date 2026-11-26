import Foundation

/// 私聊渲染组装器与流式拼接状态机（与 `ChatRender.kt` 对齐）。
public enum ChatRender: Sendable {
    public enum Item: Equatable, Sendable {
        public struct MessageData: Equatable, Sendable {
            public let fromUser: Bool
            public let markdown: String
            public let turn: Int64?
            public let seq: Int64?
            public let speakerName: String?
            public let speakerColor: String?
            public let wholeFileJson: String?

            public init(
                fromUser: Bool,
                markdown: String,
                turn: Int64? = nil,
                seq: Int64? = nil,
                speakerName: String? = nil,
                speakerColor: String? = nil,
                wholeFileJson: String? = nil
            ) {
                self.fromUser = fromUser
                self.markdown = markdown
                self.turn = turn
                self.seq = seq
                self.speakerName = speakerName
                self.speakerColor = speakerColor
                self.wholeFileJson = wholeFileJson
            }

            public func withSpeaker(name: String?, color: String?) -> MessageData {
                MessageData(
                    fromUser: fromUser,
                    markdown: markdown,
                    turn: turn,
                    seq: seq,
                    speakerName: name,
                    speakerColor: color,
                    wholeFileJson: wholeFileJson
                )
            }

            public func withSeq(_ seq: Int64?) -> MessageData {
                MessageData(
                    fromUser: fromUser,
                    markdown: markdown,
                    turn: turn,
                    seq: seq,
                    speakerName: speakerName,
                    speakerColor: speakerColor,
                    wholeFileJson: wholeFileJson
                )
            }
        }

        public struct ImageData: Equatable, Sendable {
            public let ref: String?
            public let name: String?
            public let attachmentRefJson: String?

            public init(ref: String?, name: String?, attachmentRefJson: String? = nil) {
                self.ref = ref
                self.name = name
                self.attachmentRefJson = attachmentRefJson
            }
        }

        case message(MessageData)
        case toolCard(name: String?, summary: String?)
        case image(ImageData)
        case systemLine(String?)
        case fileAttachment(name: String?, refJson: String)
        case interactiveCard(Cards.Card)
        case btw(id: String?, text: String, from: String?)
        case presentedFile(name: String, sessionId: String, seq: Int64, index: Int)
    }

    public struct Page: Equatable, Sendable {
        public let items: [Item]
        public let cursor: JsonValue?
        public let exhausted: Bool

        public init(items: [Item], cursor: JsonValue?, exhausted: Bool) {
            self.items = items
            self.cursor = cursor
            self.exhausted = exhausted
        }
    }

    public enum AppendDecision: Equatable, Sendable {
        case add(Item)
        case upgrade(index: Int, item: Item.MessageData)
    }

    public static func render(_ page: DsbotModels.ChatPage) -> Page {
        var out: [Item] = []
        for raw in page.items {
            if let item = renderItem(raw) {
                out.append(item)
            }
            out.append(contentsOf: extras(raw))
        }
        return Page(items: out, cursor: page.cursor, exhausted: page.cursor == nil)
    }

    public static func renderItem(_ raw: DsbotModels.ChatItem) -> Item? {
        guard let obj = raw.raw?.objectValue else { return nil }
        switch raw.kind {
        case "user":
            guard let text = raw.text else { return nil }
            return .message(Item.MessageData(fromUser: true, markdown: text, turn: raw.turn, seq: raw.seq))
        case "bot":
            guard let text = raw.text else { return nil }
            let file = obj.object("whole")?.object("file")
            let wholeJson: String? = (file?.string("attachmentId") != nil) ? file.map { Json.encode(.object($0)) } : nil
            return .message(Item.MessageData(
                fromUser: false,
                markdown: text,
                turn: raw.turn,
                seq: raw.seq,
                wholeFileJson: wholeJson
            ))
        case "tool":
            return .toolCard(
                name: obj.object("call")?.nonEmptyString("name"),
                summary: obj.nonEmptyString("label") ?? obj.nonEmptyString("status")
            )
        case "image":
            let image = obj.object("image")
            let refJson = (image?.contains("attachmentId") == true) ? image.map { Json.encode(.object($0)) } : nil
            return .image(Item.ImageData(
                ref: image?.nonEmptyString("url") ?? image?.nonEmptyString("data"),
                name: image?.nonEmptyString("name"),
                attachmentRefJson: refJson
            ))
        case "file":
            if let file = obj.object("file") ?? obj.object("attachment"), file.contains("attachmentId") {
                return .fileAttachment(name: file.nonEmptyString("name"), refJson: Json.encode(.object(file)))
            }
            return nil
        case "answer", "closed", "event":
            if let card = Cards.fromChatItem(raw) {
                return .interactiveCard(card)
            }
            if obj.nonEmptyString("role") == "btw", let text = raw.text, !text.isEmpty {
                let id = obj.nonEmptyString("id") ?? obj.nonEmptyString("callId")
                let from = obj.nonEmptyString("from") ?? obj.nonEmptyString("name")
                return .btw(id: id, text: text, from: from)
            }
            if let note = obj.nonEmptyString("note") ?? obj.nonEmptyString("question") ?? obj.nonEmptyString("text") {
                return .systemLine(note)
            }
            return nil
        default:
            return nil
        }
    }

    public static func extras(_ raw: DsbotModels.ChatItem) -> [Item] {
        guard let obj = raw.raw?.objectValue else { return [] }
        var out: [Item] = []
        if let files = obj.array("files") {
            for f in files {
                guard let o = f.objectValue,
                      let sessionId = o.nonEmptyString("sessionId"),
                      let seq = o.int64("seq")
                else { continue }
                let index = o.int("index") ?? 0
                out.append(.presentedFile(
                    name: o.nonEmptyString("name") ?? "file",
                    sessionId: sessionId,
                    seq: seq,
                    index: index
                ))
            }
        }
        if let imageRefs = obj.array("imageRefs") {
            for r in imageRefs {
                guard let o = r.objectValue, o.nonEmptyString("attachmentId") != nil else { continue }
                out.append(.image(Item.ImageData(
                    ref: nil,
                    name: o.nonEmptyString("name"),
                    attachmentRefJson: Json.encode(.object(o))
                )))
            }
        }
        return out
    }

    public static func gallery(items: [Item], tapped: Item.ImageData) -> (images: [Item.ImageData], index: Int) {
        let images: [Item.ImageData] = items.compactMap { item in
            if case .image(let img) = item, img.attachmentRefJson != nil { return img }
            return nil
        }
        if let idx = images.firstIndex(of: tapped) {
            return (images, idx)
        }
        return ([tapped], 0)
    }

    public final class StreamBuffer {
        private struct Writing {
            var botId: String?
            var sessionId: String?
            var attemptId: String?
            var text: String = ""
            var ended: Bool = false
        }

        private var writing: Writing?

        public init() {}

        public func onStream(
            botId: String?,
            sessionId: String?,
            attemptId: String?,
            phase: String?,
            text: String?
        ) -> String? {
            if phase == "start" {
                writing = Writing(botId: botId, sessionId: sessionId, attemptId: attemptId)
                return ""
            }
            guard var w = writing else { return nil }
            if let attemptId, let activeId = w.attemptId, attemptId != activeId {
                return nil
            }
            switch phase {
            case nil:
                if let text { w.text += text }
                writing = w
                return w.text
            case "end":
                if !w.ended, let text { w.text += text }
                w.ended = true
                writing = w
                return w.text
            default:
                return nil
            }
        }

        public func settle() -> String? {
            guard let w = writing else { return nil }
            writing = nil
            return w.text.isEmpty ? nil : w.text
        }

        public var inProgress: Bool {
            writing.map { !$0.ended } ?? false
        }
    }

    public static func appendBotMessage(items: [Item], text: String, seq: Int64?) -> AppendDecision {
        if let lastItem = items.last,
           case .message(let msg) = lastItem,
           !msg.fromUser,
           msg.seq == nil,
           msg.markdown == text
        {
            return .upgrade(index: items.count - 1, item: msg.withSeq(seq))
        }
        return .add(.message(Item.MessageData(fromUser: false, markdown: text, turn: nil, seq: seq)))
    }
}
