import Foundation

/// 解析仓库根 `package.json` 版本号与校验格式时抛出的错误。
public enum VersionSpecError: Error, Equatable, Sendable {
    case invalidJson
    case missingVersion
    case invalidFormat(String)
}

/// 语义化 `x.y.z` 版本值对象及 `versionCode` 派生公式。
public struct AppVersion: Equatable, Sendable, CustomStringConvertible {
    public let major: Int
    public let minor: Int
    public let patch: Int

    public init(major: Int, minor: Int, patch: Int) {
        self.major = major
        self.minor = minor
        self.patch = patch
    }

    public var description: String {
        "\(major).\(minor).\(patch)"
    }

    public var versionCode: Int {
        major * 10000 + minor * 100 + patch
    }
}

/// 版本号单一来源校验逻辑（与 `scripts/package-version.mts`、`VersionSpec.kt` 对齐）。
public enum VersionSpec: Sendable {
    /// 仅接受纯 ASCII 数字的 `MAJOR.MINOR.PATCH` 三段格式。
    public static func parse(_ raw: String) throws -> AppVersion {
        let parts = raw.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else {
            throw VersionSpecError.invalidFormat(raw)
        }
        var numbers: [Int] = []
        numbers.reserveCapacity(3)
        for part in parts {
            guard !part.isEmpty, part.unicodeScalars.allSatisfy({ $0 >= "0" && $0 <= "9" }) else {
                throw VersionSpecError.invalidFormat(raw)
            }
            guard let value = Int(part) else {
                throw VersionSpecError.invalidFormat(raw)
            }
            numbers.append(value)
        }
        return AppVersion(major: numbers[0], minor: numbers[1], patch: numbers[2])
    }

    /// 从 `package.json` 原文解析并校验 `version` 字段。
    public static func versionFromPackageJson(_ raw: String) throws -> AppVersion {
        guard let data = raw.data(using: .utf8) else {
            throw VersionSpecError.invalidJson
        }
        let parsed: Any
        do {
            parsed = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw VersionSpecError.invalidJson
        }
        guard let object = parsed as? [String: Any] else {
            throw VersionSpecError.invalidJson
        }
        guard let versionString = object["version"] as? String else {
            throw VersionSpecError.missingVersion
        }
        return try parse(versionString)
    }
}
