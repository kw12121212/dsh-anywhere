import Foundation

/// 远端 DS-Bot / dsh 版本读取、基准比对与语义化比较（与 `DsbotVersions.kt` 对齐）。
public enum DsbotVersions: Sendable {
    public static let baselineDsBot = "0.3.10"
    public static let baselineDsh = "0.2.2-rc.3"

    public struct Remote: Equatable, Sendable {
        public let dsBot: String?
        public let dsh: String?

        public init(dsBot: String?, dsh: String?) {
            self.dsBot = dsBot
            self.dsh = dsh
        }
    }

    public enum Verdict: Equatable, Sendable {
        case matched
        case mismatched
        case unknown
    }

    public struct Banner: Equatable, Sendable {
        public let dsBot: String
        public let dsh: String

        public init(dsBot: String, dsh: String) {
            self.dsBot = dsBot
            self.dsh = dsh
        }
    }

    public static func fromUpdateCheck(_ value: JsonValue) -> Remote {
        let obj = value.objectValue
        let dshObj = obj?.object("dsh")
        return Remote(
            dsBot: obj?.string("current"),
            dsh: dshObj?.string("current")
        )
    }

    public static func againstBaseline(
        _ remote: Remote,
        dsBotBaseline: String = baselineDsBot,
        dshBaseline: String = baselineDsh
    ) -> Verdict {
        guard let dsBot = remote.dsBot, let dsh = remote.dsh else {
            return .unknown
        }
        return (dsBot == dsBotBaseline && dsh == dshBaseline) ? .matched : .mismatched
    }

    public static func banner(remote: Remote?, capabilities: Capabilities) -> Banner? {
        let mismatch = remote.map { againstBaseline($0) == .mismatched } ?? false
        if !mismatch && !capabilities.versionMismatch {
            return nil
        }
        return Banner(dsBot: remote?.dsBot ?? "?", dsh: remote?.dsh ?? "?")
    }

    /// 语义化版本比较：`a > b` 返回正数，相等返回 `0`，`a < b` 返回负数；解析失败按字典序比较。
    public static func compare(_ a: String, _ b: String) -> Int {
        guard let pa = parse(a), let pb = parse(b) else {
            if a == b { return 0 }
            return a < b ? -1 : 1
        }
        for i in 0..<3 where pa.nums[i] != pb.nums[i] {
            return pa.nums[i] < pb.nums[i] ? -1 : 1
        }
        switch (pa.pre, pb.pre) {
        case (nil, nil):
            return 0
        case (nil, _):
            return 1
        case (_, nil):
            return -1
        case (let x?, let y?):
            let xs = x.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            let ys = y.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            let limit = max(xs.count, ys.count)
            for i in 0..<limit {
                if i >= xs.count { return -1 }
                if i >= ys.count { return 1 }
                let xa = xs[i]
                let ya = ys[i]
                if let xn = Int(xa), let yn = Int(ya) {
                    if xn != yn { return xn < yn ? -1 : 1 }
                } else if xa != ya {
                    return xa < ya ? -1 : 1
                }
            }
            return 0
        }
    }

    private struct ParsedSemver {
        let nums: [Int]
        let pre: String?
    }

    private static func parse(_ v: String) -> ParsedSemver? {
        let trimmed = v.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let regex = try? NSRegularExpression(pattern: #"^v?(\d+)\.(\d+)\.(\d+)(?:-([\w.]+))?(?:\+[\w.]+)?$"#) else {
            return nil
        }
        let ns = trimmed as NSString
        guard let match = regex.firstMatch(in: trimmed, range: NSRange(location: 0, length: ns.length)),
              let n0 = Int(ns.substring(with: match.range(at: 1))),
              let n1 = Int(ns.substring(with: match.range(at: 2))),
              let n2 = Int(ns.substring(with: match.range(at: 3)))
        else {
            return nil
        }
        let preRange = match.range(at: 4)
        let pre: String? = (preRange.location != NSNotFound && preRange.length > 0) ? ns.substring(with: preRange) : nil
        return ParsedSemver(nums: [n0, n1, n2], pre: pre)
    }
}
