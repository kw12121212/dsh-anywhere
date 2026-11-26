import Foundation

/// RFC 3986 相对 `Location` 解析（覆盖绝对地址、协议相对、查询串相对、片段与相对路径）。
public enum HttpLocation: Sendable {
    public static func resolve(base: String, location: String) -> String? {
        let loc = location.trimmingCharacters(in: .whitespacesAndNewlines)
        let baseTrimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !loc.isEmpty,
              let baseParsed = parseBase(baseTrimmed)
        else {
            return nil
        }

        if let scheme = leadingScheme(in: loc)?.lowercased() {
            guard scheme == "http" || scheme == "https",
                  HarnessUrl.canonicalOrigin(loc) != nil
            else {
                return nil
            }
            return loc
        }

        if loc.hasPrefix("//") {
            let candidate = "\(baseParsed.scheme):\(loc)"
            return HarnessUrl.canonicalOrigin(candidate) != nil ? candidate : nil
        }

        if loc.hasPrefix("#") {
            let baseQueryPart = baseParsed.query.map { "?\($0)" } ?? ""
            return "\(baseParsed.scheme)://\(baseParsed.authority)\(baseParsed.path)\(baseQueryPart)\(loc)"
        }

        if loc.hasPrefix("?") {
            return "\(baseParsed.scheme)://\(baseParsed.authority)\(baseParsed.path)\(loc)"
        }

        let (rawPath, query, fragment) = splitPathQueryFragment(loc)
        let mergedPath: String
        if rawPath.hasPrefix("/") {
            mergedPath = normalizeDotSegments(rawPath)
        } else {
            let dir: String
            if let lastSlash = baseParsed.path.lastIndex(of: "/") {
                dir = String(baseParsed.path[...lastSlash])
            } else {
                dir = "/"
            }
            mergedPath = normalizeDotSegments(dir + rawPath)
        }

        var out = "\(baseParsed.scheme)://\(baseParsed.authority)\(mergedPath)"
        if let query {
            out += "?\(query)"
        }
        if let fragment {
            out += "#\(fragment)"
        }
        return out
    }

    private struct BaseParts {
        let scheme: String
        let authority: String
        let path: String
        let query: String?
    }

    private static func parseBase(_ raw: String) -> BaseParts? {
        guard let scheme = leadingScheme(in: raw)?.lowercased(),
              scheme == "http" || scheme == "https",
              let colon = raw.firstIndex(of: ":")
        else {
            return nil
        }
        let afterColon = raw[raw.index(after: colon)...]
        guard afterColon.hasPrefix("//") else { return nil }
        let rest = afterColon.dropFirst(2)
        let beforeFragment = rest.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? rest
        let (beforeQuery, query): (Substring, String?)
        if let qIdx = beforeFragment.firstIndex(of: "?") {
            beforeQuery = beforeFragment[..<qIdx]
            query = String(beforeFragment[beforeFragment.index(after: qIdx)...])
        } else {
            beforeQuery = beforeFragment
            query = nil
        }
        let authority: String
        let path: String
        if let slash = beforeQuery.firstIndex(of: "/") {
            authority = String(beforeQuery[..<slash])
            path = String(beforeQuery[slash...])
        } else {
            authority = String(beforeQuery)
            path = "/"
        }
        guard !authority.isEmpty else { return nil }
        return BaseParts(scheme: scheme, authority: authority, path: path.isEmpty ? "/" : path, query: query)
    }

    private static func splitPathQueryFragment(_ text: String) -> (path: String, query: String?, fragment: String?) {
        let beforeFragment: Substring
        let fragment: String?
        if let hash = text.firstIndex(of: "#") {
            beforeFragment = text[..<hash]
            fragment = String(text[text.index(after: hash)...])
        } else {
            beforeFragment = text[...]
            fragment = nil
        }
        if let q = beforeFragment.firstIndex(of: "?") {
            return (String(beforeFragment[..<q]), String(beforeFragment[beforeFragment.index(after: q)...]), fragment)
        }
        return (String(beforeFragment), nil, fragment)
    }

    private static func normalizeDotSegments(_ path: String) -> String {
        let hasTrailingSlash = path.hasSuffix("/") || path.hasSuffix("/.") || path.hasSuffix("/..") || path == "." || path == ".."
        var stack: [Substring] = []
        for segment in path.split(separator: "/", omittingEmptySubsequences: true) {
            if segment == "." {
                continue
            } else if segment == ".." {
                if !stack.isEmpty {
                    stack.removeLast()
                }
            } else {
                stack.append(segment)
            }
        }
        var result = "/" + stack.joined(separator: "/")
        if hasTrailingSlash && result != "/" {
            result += "/"
        }
        return result
    }

    private static func leadingScheme(in text: String) -> String? {
        guard let first = text.unicodeScalars.first,
              (first >= "a" && first <= "z") || (first >= "A" && first <= "Z")
        else {
            return nil
        }
        var idx = text.unicodeScalars.startIndex
        while idx < text.unicodeScalars.endIndex {
            let s = text.unicodeScalars[idx]
            if s == ":" {
                return String(text.unicodeScalars[text.unicodeScalars.startIndex..<idx])
            }
            if (s >= "a" && s <= "z") || (s >= "A" && s <= "Z") || (s >= "0" && s <= "9") ||
                s == "+" || s == "." || s == "-" {
                idx = text.unicodeScalars.index(after: idx)
            } else {
                return nil
            }
        }
        return nil
    }
}
