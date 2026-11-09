# RouteShelf Windows development preview

Optional read-only Windows frontend using the repository's existing Java core directly. Network/TLS/discovery, Drive API, public-share parsing and integrity policies remain in app/src/main/java. The Windows adapter does not replace RouteShelf's current Android IPv6 implementation, branding, package name, OAuth registration or signer.

## Build and validation

Requires Windows x64, JDK17+ and .NET10 SDK:

```powershell
./windows/restore-java.ps1
./windows/check.ps1 -JavaHome 'C:/path/to/jdk'
./windows/package.ps1 -JavaHome 'C:/path/to/jdk'
```

Java dependencies are the existing project dependencies, restored from Maven Central with SHA256 pins. On desktop the original JVM test org.json implementation supplies Android's platform JSON API. No runtime, binary, credential or generated output is tracked. The package command builds a self-contained .NET10 application and includes a supplied JDK runtime with its legal notices. Package from a reviewed JDK distribution and provide corresponding source as required by its license. This is an unsigned development preview.

Verified against upstream 0456bf7abb6e0bd204895ec4eb268dfcb8f5069a: 189 shared Java/adapter tests passed (including local IPv4/IPv6 TLS), .NET10 WPF build zero warnings/errors, and21 Windows checks passed including DPAPI, simulated credential restore, stream bounds, dependency tamper rejection and actual Java pipe framing. The check script runs19 in-process assertions; passing a staged application directory to GDirect.Tests.dll additionally enables the two real JVM pipe assertions. No public-IP probing or real-account transfer was performed. Full Android build/Lint, fresh .NET10 CI run, real networks/accounts/downloads and full accessibility acceptance remain pending.

## Features and data compatibility

The WPF frontend supports read-only folder browsing, search, pagination, downloads/exports and public single-file share downloads without login. Desktop OAuth uses the system browser, state and PKCE; fixed Google token exchange runs through the same Java direct transport. First authorization and expired/revoked grants can still require browser connectivity. Google External Testing grants requesting Drive scope can expire after seven days.

Windows current-user DPAPI encrypts refresh credentials and desktop OAuth configuration; access tokens remain in memory. The existing local prototype profile path %LOCALAPPDATA%/GDirectLite/Windows/account.dpapi and executable identity are deliberately retained for compatibility; the visible app is RouteShelf. Disconnect removes the local encrypted profile. Android's RememberedAuthorization and Google Play SDK flows are unaffected.

Communication is over private parent/child pipes, never bearer tokens in argv or logs. The shell checks Java dependency hashes against an embedded manifest, rejects unknown JARs, bounds response reads, and restarts the child after protocol errors. Hash checks are not Authenticode signing or protection against malware under the same Windows user.

## Scope and integration

Two shared-code additions explicitly admit only oauth2.googleapis.com to DirectRoutes and the fixed Google frontend host check, enabling desktop token exchange. Public-link host rules and DriveTransport credential boundaries do not widen. Manual connection checks use DirectRoutes.check rather than resetting live route generation. The desktop composition preserves the latest upstream parser and dual-stack routing rather than importing earlier copies.

The platform shell still lacks Android's full view/action coverage, upload/sync/queues, an installer, code signing and independent security review. It does not guarantee every network is reachable. Windows visual roles are documented in DESIGN.md. Production licensing is GPL-3.0-only as in the repository; bundled dependencies retain their notices.
