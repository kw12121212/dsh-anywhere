import Foundation
import XCTest
@testable import DshCore

final class VersionSpecTests: XCTestCase {
    private func repoRootPackageJsonURL() -> URL {
        // .../macos/Core/Tests/DshCoreTests/VersionSpecTests.swift -> 向上五级到仓库根
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("package.json")
    }

    func testParseValidVersionsAndVersionCode() throws {
        let v100 = try VersionSpec.parse("1.0.0")
        XCTAssertEqual(v100, AppVersion(major: 1, minor: 0, patch: 0))
        XCTAssertEqual(v100.description, "1.0.0")
        XCTAssertEqual(v100.versionCode, 10000)

        let v2137 = try VersionSpec.parse("2.13.7")
        XCTAssertEqual(v2137.versionCode, 21307)
    }

    func testParseRejectsInvalidFormats() {
        for invalid in ["", "dev", "1.0", "1.0.0.0", "1.0.0-rc1", "v1.0.0", " 1.0.0", "-1.0.0", "1..0", "١.0.0"] {
            XCTAssertThrowsError(try VersionSpec.parse(invalid), "应拒绝非法版本: \(invalid)") { error in
                XCTAssertEqual(error as? VersionSpecError, .invalidFormat(invalid))
            }
        }
    }

    func testRepoRootPackageJsonMatchesSingleSourceContract() throws {
        let raw = try String(contentsOf: repoRootPackageJsonURL(), encoding: .utf8)
        let parsed = try VersionSpec.versionFromPackageJson(raw)
        XCTAssertGreaterThanOrEqual(parsed.major, 1)
        XCTAssertEqual(parsed.versionCode, parsed.major * 10000 + parsed.minor * 100 + parsed.patch)
    }

    func testVersionFromPackageJsonRejectsMalformedOrMissing() {
        XCTAssertThrowsError(try VersionSpec.versionFromPackageJson("not json")) { error in
            XCTAssertEqual(error as? VersionSpecError, .invalidJson)
        }
        XCTAssertThrowsError(try VersionSpec.versionFromPackageJson("[]")) { error in
            XCTAssertEqual(error as? VersionSpecError, .invalidJson)
        }
        XCTAssertThrowsError(try VersionSpec.versionFromPackageJson("{}")) { error in
            XCTAssertEqual(error as? VersionSpecError, .missingVersion)
        }
        XCTAssertThrowsError(try VersionSpec.versionFromPackageJson(#"{"version": 123}"#)) { error in
            XCTAssertEqual(error as? VersionSpecError, .missingVersion)
        }
        XCTAssertThrowsError(try VersionSpec.versionFromPackageJson(#"{"version": "dev"}"#)) { error in
            XCTAssertEqual(error as? VersionSpecError, .invalidFormat("dev"))
        }
    }

    func testAppIdentityMatchesCanonicalConstants() {
        XCTAssertEqual(AppIdentity.displayName, "DSH Anywhere")
        XCTAssertEqual(AppIdentity.bundleIdentifier, "work.keepwriting.dsh-anywhere")
        XCTAssertEqual(AppIdentity.copyright, "© 2026 Keepwriting")
        XCTAssertEqual(AppIdentity.minimumMacOSVersion, "14.0")
    }
}
