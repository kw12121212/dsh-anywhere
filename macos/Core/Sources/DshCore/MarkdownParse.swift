import Foundation

/// 轻量 Markdown 块级/行内解析（与 `MarkdownParse.kt` 对齐，无全局可变状态）。
public enum MarkdownParse: Sendable {
    public enum InlineKind: Equatable, Sendable {
        case plain
        case bold
        case italic
        case code
        case link
    }

    public struct InlinePart: Equatable, Sendable {
        public let kind: InlineKind
        public let text: String
        public let url: String?

        public init(kind: InlineKind, text: String, url: String? = nil) {
            self.kind = kind
            self.text = text
            self.url = url
        }
    }

    public enum MdBlock: Equatable, Sendable {
        case heading(level: Int, parts: [InlinePart])
        case bullet(parts: [InlinePart])
        case ordered(index: String, parts: [InlinePart])
        case code(String)
        case table(header: [[InlinePart]], rows: [[[InlinePart]]])
        case paragraph(parts: [InlinePart])
        case task(checked: Bool, parts: [InlinePart])
        case image(alt: String, src: String)
    }

    private static let orderedPrefixRegex = try! NSRegularExpression(pattern: #"^\d+\. "#)
    private static let taskRegex = try! NSRegularExpression(pattern: #"^[-*] \[([ xX])\] (.*)$"#)
    private static let imageLineRegex = try! NSRegularExpression(pattern: #"^!\[([^\]]*)\]\((<[^>]+>|[^)\s]+)\)$"#)
    private static let boldRegex = try! NSRegularExpression(pattern: #"\*\*(.+?)\*\*"#)
    private static let italicRegex = try! NSRegularExpression(pattern: #"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)"#)
    private static let codeRegex = try! NSRegularExpression(pattern: #"`([^`]+)`"#)
    private static let linkRegex = try! NSRegularExpression(pattern: #"\[([^\]]+)\]\(([^)]+)\)"#)
    private static let tsvControlRegex = try! NSRegularExpression(pattern: #"[\t\r\n]+"#)

    public static func parseBlocks(_ markdown: String) -> [MdBlock] {
        var out: [MdBlock] = []
        let lines = splitLines(markdown)
        var i = 0
        while i < lines.count {
            let line = lines[i]
            if line.hasPrefix("```") {
                var codeLines: [String] = []
                i += 1
                while i < lines.count && !lines[i].hasPrefix("```") {
                    codeLines.append(lines[i])
                    i += 1
                }
                i += 1
                out.append(.code(codeLines.joined(separator: "\n")))
                continue
            }
            if line.hasPrefix("### ") {
                out.append(.heading(level: 3, parts: parseInline(String(line.dropFirst(4)))))
            } else if line.hasPrefix("## ") {
                out.append(.heading(level: 2, parts: parseInline(String(line.dropFirst(3)))))
            } else if line.hasPrefix("# ") {
                out.append(.heading(level: 1, parts: parseInline(String(line.dropFirst(2)))))
            } else if let task = matchTask(line) {
                out.append(.task(checked: task.checked, parts: parseInline(task.body)))
            } else if let img = matchImage(line.trimmingCharacters(in: .whitespacesAndNewlines)) {
                out.append(.image(alt: img.alt, src: img.src))
            } else if line.hasPrefix("- ") {
                out.append(.bullet(parts: parseInline(String(line.dropFirst(2)))))
            } else if let ordered = matchOrdered(line) {
                out.append(.ordered(index: ordered.index, parts: parseInline(ordered.body)))
            } else if line.hasPrefix("|"), i + 1 < lines.count, lines[i + 1].hasPrefix("|") {
                let header = splitTableRow(line).map(parseInline)
                i += 2
                var rows: [[[InlinePart]]] = []
                while i < lines.count, lines[i].hasPrefix("|") {
                    rows.append(splitTableRow(lines[i]).map(parseInline))
                    i += 1
                }
                out.append(.table(header: header, rows: rows))
                continue
            } else if !line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                out.append(.paragraph(parts: parseInline(line)))
            }
            i += 1
        }
        return out
    }

    public static func parseInline(_ text: String) -> [InlinePart] {
        var parts: [InlinePart] = []
        var rest = text
        struct Hit {
            let range: NSRange
            let part: InlinePart
        }
        while !rest.isEmpty {
            let ns = rest as NSString
            let full = NSRange(location: 0, length: ns.length)
            var candidates: [Hit] = []

            if let m = boldRegex.firstMatch(in: rest, range: full) {
                candidates.append(Hit(range: m.range, part: InlinePart(kind: .bold, text: ns.substring(with: m.range(at: 1)))))
            }
            if let m = codeRegex.firstMatch(in: rest, range: full) {
                candidates.append(Hit(range: m.range, part: InlinePart(kind: .code, text: ns.substring(with: m.range(at: 1)))))
            }
            if let m = linkRegex.firstMatch(in: rest, range: full) {
                candidates.append(Hit(
                    range: m.range,
                    part: InlinePart(kind: .link, text: ns.substring(with: m.range(at: 1)), url: ns.substring(with: m.range(at: 2)))
                ))
            }
            if let m = italicRegex.firstMatch(in: rest, range: full) {
                candidates.append(Hit(range: m.range, part: InlinePart(kind: .italic, text: ns.substring(with: m.range(at: 1)))))
            }

            guard let hit = candidates.min(by: { $0.range.location < $1.range.location }) else {
                parts.append(InlinePart(kind: .plain, text: rest))
                break
            }
            if hit.range.location > 0 {
                parts.append(InlinePart(kind: .plain, text: ns.substring(to: hit.range.location)))
            }
            parts.append(hit.part)
            rest = ns.substring(from: hit.range.location + hit.range.length)
        }
        return parts
    }

    public static func plain(_ parts: [InlinePart]) -> String {
        parts.map(\.text).joined()
    }

    public static func tsv(header: [[InlinePart]], rows: [[[InlinePart]]]) -> String {
        func cell(_ parts: [InlinePart]) -> String {
            let text = plain(parts)
            let ns = text as NSString
            return tsvControlRegex.stringByReplacingMatches(
                in: text,
                range: NSRange(location: 0, length: ns.length),
                withTemplate: " "
            ).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let width = header.count
        var lines = [header.map(cell).joined(separator: "\t")]
        for row in rows {
            let count = max(width, row.count)
            let cells = (0..<count).map { idx -> String in
                idx < row.count ? cell(row[idx]) : ""
            }
            lines.append(cells.joined(separator: "\t"))
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - 内部解析辅助

    private static func splitLines(_ text: String) -> [String] {
        var normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        normalized = normalized.replacingOccurrences(of: "\r", with: "\n")
        return normalized.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    }

    private static func matchTask(_ line: String) -> (checked: Bool, body: String)? {
        let ns = line as NSString
        guard let m = taskRegex.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        let flag = ns.substring(with: m.range(at: 1))
        let body = ns.substring(with: m.range(at: 2))
        return (!flag.trimmingCharacters(in: .whitespaces).isEmpty, body)
    }

    private static func matchImage(_ line: String) -> (alt: String, src: String)? {
        let ns = line as NSString
        guard let m = imageLineRegex.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        let alt = ns.substring(with: m.range(at: 1))
        var src = ns.substring(with: m.range(at: 2))
        if src.hasPrefix("<") && src.hasSuffix(">") && src.count >= 2 {
            src = String(src.dropFirst().dropLast())
        }
        return (alt, src.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private static func matchOrdered(_ line: String) -> (index: String, body: String)? {
        let ns = line as NSString
        guard let m = orderedPrefixRegex.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        let prefix = ns.substring(with: m.range)
        let index = String(prefix.dropLast(2))
        let body = ns.substring(from: m.range.length)
        return (index, body)
    }

    private static func splitTableRow(_ line: String) -> [String] {
        var trimmed = line
        while trimmed.hasPrefix("|") { trimmed.removeFirst() }
        while trimmed.hasSuffix("|") { trimmed.removeLast() }
        return trimmed.split(separator: "|", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    }
}
