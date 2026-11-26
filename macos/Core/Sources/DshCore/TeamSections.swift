import Foundation

/// 团队侧栏分组解码、名称校验与请求体构造（与 `TeamSections.kt` 对齐）。
public enum TeamSections: Sendable {
    public struct Section: Equatable, Sendable {
        public let id: String
        public let name: String
        public let collapsed: Bool
        public let color: String?

        public init(id: String, name: String, collapsed: Bool, color: String?) {
            self.id = id
            self.name = name
            self.collapsed = collapsed
            self.color = color
        }
    }

    public static let nameMax = 32

    private static let quoteEdgeRegex = try! NSRegularExpression(pattern: #"^[\"'“”「『《]+|[\"'“”」』》]+$"#)
    private static let multiSpaceRegex = try! NSRegularExpression(pattern: #"\s+"#)

    public static func decode(_ state: JsonObject?) -> [Section] {
        guard let arr = state?.array("sections") else { return [] }
        var seen = Set<String>()
        return arr.compactMap { v in
            guard let o = v.objectValue,
                  let id = o.nonEmptyString("id"),
                  seen.insert(id).inserted
            else {
                return nil
            }
            return Section(
                id: id,
                name: o.string("name") ?? I18n.tr("未命名分组", "Untitled section"),
                collapsed: o.bool("collapsed") == true,
                color: o.string("color")
            )
        }
    }

    public static func clean(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let nsTrimmed = trimmed as NSString
        let unquoted = quoteEdgeRegex.stringByReplacingMatches(
            in: trimmed,
            range: NSRange(location: 0, length: nsTrimmed.length),
            withTemplate: ""
        ).trimmingCharacters(in: .whitespacesAndNewlines)
        let nsUnquoted = unquoted as NSString
        let collapsed = multiSpaceRegex.stringByReplacingMatches(
            in: unquoted,
            range: NSRange(location: 0, length: nsUnquoted.length),
            withTemplate: " "
        )
        if collapsed.count > nameMax {
            return String(collapsed.prefix(nameMax))
        }
        return collapsed
    }

    public static func nameProblem(sections: [Section], name: String, selfId: String? = nil) -> String? {
        let next = clean(name)
        if next.isEmpty {
            return I18n.tr("请输入分组名称", "Enter a section name")
        }
        let key = next.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if sections.contains(where: { $0.id != selfId && $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == key }) {
            return I18n.tr("已有同名分组「\(next)」", "Another section is already called \"\(next)\"")
        }
        return nil
    }

    public static func move(_ ids: [String], from: Int, to: Int) -> [String] {
        guard ids.indices.contains(from), ids.indices.contains(to), from != to else {
            return ids
        }
        var next = ids
        let moved = next.remove(at: from)
        next.insert(moved, at: to)
        return next
    }

    public static func isPermutation(current: [String], next: [String]) -> Bool {
        next.count == current.count && Set(next).count == next.count && Set(next) == Set(current)
    }

    public static func targetIndex(from: Int, offset: Double, rowHeight: Double, count: Int) -> Int {
        guard count > 0, rowHeight > 0 else { return from }
        let shift = Int((offset / rowHeight).rounded())
        return min(max(from + shift, 0), count - 1)
    }

    public enum Requests: Sendable {
        public static func create(_ name: String) -> String {
            let cleaned = clean(name)
            return cleaned.isEmpty ? "{}" : "{\"name\":\(JsonString.of(cleaned))}"
        }

        public static func rename(id: String, name: String) -> String {
            "{\"id\":\(JsonString.of(id)),\"name\":\(JsonString.of(clean(name)))}"
        }

        public static func collapse(id: String, collapsed: Bool) -> String {
            "{\"id\":\(JsonString.of(id)),\"collapsed\":\(collapsed)}"
        }

        public static func order(_ ids: [String]) -> String {
            "{\"ids\":[\(ids.map(JsonString.of).joined(separator: ","))]}"
        }

        public static func delete(id: String) -> String {
            "{\"id\":\(JsonString.of(id))}"
        }

        public static func moveEntry(entryId: String, sectionId: String?) -> String {
            "{\"id\":\(JsonString.of(entryId)),\"sectionId\":\(sectionId.map(JsonString.of) ?? "null")}"
        }
    }
}
