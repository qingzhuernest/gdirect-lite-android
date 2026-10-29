import XCTest
import Security
import Foundation
@testable import GDirectCore

final class TLSTests: XCTestCase {
    // Public, self-signed test certificate; no private key is shipped or installed.
    private let certificate = "MIIDLTCCAhWgAwIBAgIBKjANBgkqhkiG9w0BAQsFADAkMSIwIAYDVQQDDBlHRGlyZWN0IE9mZmxpbmUgVGVzdCBSb290MB4XDTI2MTAyOTEzMTkwN1oXDTI3MTAyOTEzMTkwN1owGzEZMBcGA1UEAwwQZHJpdmUuZ29vZ2xlLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKJHXbaogTDpm9bls+WoWCkk0EZblai3PRSUhd0RPRwXv8cGpeR3TkYGLs072KMJ2Kz/2AQYimVGmHxD5eODM3CMqroEYayMu4VhTJl0ZsMRr8ZiUizR/dUL5CVGGGn7UZycmGTpX3baEMtmtHZW+4WR0g8iEK48+bMLX2VlgyMAo2/OL57r8jMVIvED85EFK5Tf0yBW6muaBaLLD/hRa7u6Q4GUodNbTZOg6Dcw9dccb/Ua+d3xGdq0CnoXTwi6sthJ8TkvMn7U4BDdj/I8k9Qt9uXFsvjcXWyNl4ggJNErZ1Ktg94mNJue97BOy3Vvgjzzm8w7QZndgMtKQT+Us1MCAwEAAaNzMHEwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCBaAwEwYDVR0lBAwwCgYIKwYBBQUHAwEwGwYDVR0RBBQwEoIQZHJpdmUuZ29vZ2xlLmNvbTAfBgNVHSMEGDAWgBQssCkX8S45UZMeWIr1ZbnaNHyhyzANBgkqhkiG9w0BAQsFAAOCAQEAp/6ry/FitUOiKslS19HiIy/WAWwXDaD+Xez63x8JzvfcPsVoRm191L6SzGS5ULOCD8HR8UocDp2PiAbfO/hCZjRNyTxfgpXTyVwwU/4jKrryP85ZeHrKKHNjf4Iw9hARYncDVs+olWdhJoh8iYC31ZJ8wz0QlOoO9/n5sz6h8BGE55/rX243EKBxapYxR2M2sye6mRVF78WWt1bc+htmfovOZj28e9r95OyHogY9w8gcrbyWFfou7qHSaT271cjnJikk7NVYYNH5wrp9NxfTuc9kqmbitQjGXqFT6HvnX/IbgPcBwhQdZVMdrEwhneSSNVkcMgP5jIi2++SVoZZSlA=="
    private let anchor = "MIIDDTCCAfWgAwIBAgIJAJTJWAcarpyAMA0GCSqGSIb3DQEBCwUAMCQxIjAgBgNVBAMMGUdEaXJlY3QgT2ZmbGluZSBUZXN0IFJvb3QwHhcNMjYxMDI5MTMxOTA3WhcNMzYxMDI2MTMxOTA3WjAkMSIwIAYDVQQDDBlHRGlyZWN0IE9mZmxpbmUgVGVzdCBSb290MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA6ASXbspqQExLvXFUvEO67RWsZmq+tdyjyzsj1sW4lyD+kZJxOpeHYUDW2NhYmqLO8pWbDXJWxi/BrEWgESkdEGSDPQpGHP1v4uwkAOR6sESIvfgbwlzdzzu+MbSxXbPjhxolxZvC2yZMtN/YIgTpY5TuQE8GaAljg1zLK1mXN7NmPKC9Qk7dXzs0ozW0k6qugr6+RkN1Y7Ep6xryT6RGME9EA+clOFmwT7+AbwTW8Dz0l4g5k0tSnFKCZqqbjDVL1elOC2bp5bqQx5Q38HBhqfzPUkQiSsJpLQsiqVDu4l6oeDaykHfn+ffnTaGTtPW2xHij/qGmEN2PG81vJIVPgwIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQULLApF/EuOVGTHliK9WW52jR8ocswDQYJKoZIhvcNAQELBQADggEBABKahKDSZs05ykfTr3vm61aS1Zn6lIn4eeua2NgM7rqV41s9qZxTbCd7QPE2ZRCkGxBAy9JvDR2xAy2G1KxTx4Rnm1jXeLkH4yk5uXmFZBCqOKvPLkatC0mYXVSkJrrzUakoA0nZ3PU3SSv3Rh5er/yqmgdooJQrDMdgPH7hoiFEOtrxCqZx/hTn01fK5TDwKMyzPhursR5HwBMG1WsMDKvAZRMFhcHrLxv37WtOjqSgbPyZA4Y9V0zeLrFh5ZUXbGJduEk5g5spXkcVwKxKn8NdWEZwzVmsgcOoGoqdSL+8+JB3rXWAmzZnxNylQentE7mnkZhWhJ0HT/taSGRP+ds="
    private func trust(anchor: Bool) throws -> SecTrust {
        let cert = try XCTUnwrap(SecCertificateCreateWithData(nil, Data(base64Encoded: certificate)! as CFData))
        var value: SecTrust?
        XCTAssertEqual(SecTrustCreateWithCertificates(cert, SecPolicyCreateSSL(true, "drive.google.com" as CFString), &value), errSecSuccess)
        let trust = try XCTUnwrap(value)
        SecTrustSetNetworkFetchAllowed(trust, false)
        SecTrustSetVerifyDate(trust, ISO8601DateFormatter().date(from: "2026-10-30T12:00:00Z")! as CFDate)
        if anchor {
            let root = SecCertificateCreateWithData(nil, Data(base64Encoded: self.anchor)! as CFData)!
            SecTrustSetAnchorCertificates(trust, [root] as CFArray)
            SecTrustSetAnchorCertificatesOnly(trust, true)
        }
        return trust
    }
    func testTrustedMatchingHostnameAccepted() throws {
        XCTAssertTrue(TLSIdentity.evaluate(try trust(anchor: true), hostname: "drive.google.com"))
    }
    func testWrongHostnameRejectedEvenWithTrustedAnchor() throws {
        XCTAssertFalse(TLSIdentity.evaluate(try trust(anchor: true), hostname: "evil.test"))
    }
    func testUntrustedSelfSignedCertificateRejected() throws {
        XCTAssertFalse(TLSIdentity.evaluate(try trust(anchor: false), hostname: "drive.google.com"))
    }
}
