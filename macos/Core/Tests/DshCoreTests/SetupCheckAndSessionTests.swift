import Foundation
import XCTest
@testable import DshCore

final class SetupCheckAndSessionTests: XCTestCase {
    func testSetupCheckVerifyPassesCleanUrlAndPropagatesUnreachable() {
        var probed: [String] = []
        var stages: [SetupCheck.Stage] = []
        let ok = SetupCheck.verify(
            rawUrl: "  http://127.0.0.1:13080/?token=abc123  ",
            probe: { url in
                probed.append(url)
                return .reachable
            },
            onStage: { stages.append($0) }
        )
        XCTAssertEqual(.ok(cleanUrl: "http://127.0.0.1:13080/"), ok)
        XCTAssertEqual(["http://127.0.0.1:13080/"], probed)
        XCTAssertEqual([.probe], stages)

        for reason in ProbeFailureReason.allCases {
            let res = SetupCheck.verify(
                rawUrl: "https://gamepc.tail1.ts.net:13080/",
                probe: { _ in .unreachable(reason) }
            )
            XCTAssertEqual(.unreachable(reason), res)
        }
    }

    func testSetupCheckInvalidUrlStopsBeforeProbe() {
        let cases: [(String, HarnessUrl.ErrorCategory)] = [
            ("", .empty),
            ("ftp://dsh.example.test/", .unsupportedScheme),
            ("dsh.example.test", .notAbsolute),
        ]
        for (raw, cat) in cases {
            var called = false
            let res = SetupCheck.verify(rawUrl: raw, probe: { _ in called = true; return .reachable })
            XCTAssertEqual(.invalidUrl(cat), res)
            XCTAssertFalse(called)
        }
    }

    func testSetupCheckTailnetHints() {
        XCTAssertEqual(.checkTailscale, SetupCheck.errorHint("http://gamepc:13080/"))
        XCTAssertEqual(.checkTailscale, SetupCheck.errorHint("https://gamepc.tail1.ts.net:13080/"))
        XCTAssertEqual(.checkTailscale, SetupCheck.errorHint("http://100.64.0.1/"))
        XCTAssertEqual(.checkAddress, SetupCheck.errorHint("http://10.0.2.2:13080/"))
        XCTAssertEqual(.checkAddress, SetupCheck.errorHint("https://dsh.example.test/"))
        XCTAssertEqual(.checkAddress, SetupCheck.errorHint("http://localhost:13080/"))
        XCTAssertEqual(.checkAddress, SetupCheck.errorHint("not a url"))
    }

    func testSessionAuthWithRealFixtureAndHistory() throws {
        let body = try TestFixtures.dsbotText("error-401-body.txt")
            .trimmingCharacters(in: .newlines)
        XCTAssertTrue(SessionAuth.isSessionExpired(status: 401, contentType: "text/plain; charset=utf-8", body: body))
        XCTAssertFalse(SessionAuth.isSessionExpired(status: 200, contentType: "text/plain", body: body))
        XCTAssertFalse(SessionAuth.isSessionExpired(status: 401, contentType: "text/html", body: body))

        let urls = [
            "https://dsh.example.test/?token=abc",
            "https://dsh.example.test/",
        ]
        XCTAssertTrue(SessionAuth.hasTokenInHistory(urls: urls, currentIndex: 1))
        XCTAssertFalse(SessionAuth.hasTokenInHistory(urls: urls, currentIndex: 0))
    }

    func testSessionCookiesParsingAndRoundTrip() throws {
        let parsed = try XCTUnwrap(SessionCookies.parseSetCookie(
            "dsh-auth-abc=v1.xxx; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict"
        ))
        XCTAssertEqual("dsh-auth-abc", parsed.name)
        XCTAssertEqual("v1.xxx", parsed.value)
        XCTAssertEqual("2592000", parsed.attrs["max-age"])
        XCTAssertTrue(SessionCookies.isAuthCookie(parsed.name))
        XCTAssertFalse(SessionCookies.isAuthCookie("other"))

        let header = "unrelated=1; dsh-auth-xyz=v1.abc; another=2; dsh-auth-broken"
        let auth = SessionCookies.authCookies(in: SessionCookies.parseHeader(header))
        XCTAssertEqual(1, auth.count)
        XCTAssertEqual("dsh-auth-xyz", auth[0].name)
        XCTAssertEqual("dsh-auth-xyz=v1.abc", SessionCookies.header(for: auth))

        let restored = SessionCookies.deserialize(SessionCookies.serialize(auth))
        XCTAssertEqual(auth, restored)
        XCTAssertTrue(SessionCookies.deserialize("garbage\n\ndsh-auth-x\t").isEmpty)
    }
}
