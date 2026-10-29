import XCTest
import Foundation
import Security
import Network
@testable import GDirectCore

final class LinkTests: XCTestCase {
    func testNormalizePreservesResourceKey() throws {
        let u = try DriveLinks.normalize(" https://drive.google.com/file/d/ABCDEFGHIJKL/view?resourcekey=0_abc-12&usp=sharing ")
        let q = URLComponents(url: u, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(u.host, "drive.usercontent.google.com")
        XCTAssertEqual(q.first { $0.name == "resourcekey" }?.value, "0_abc-12")
    }
    func testRejectUnsafeOrigins() {
        for raw in ["http://drive.google.com/open?id=ABCDEFGHIJKL", "https://drive.google.com.evil.test/open?id=ABCDEFGHIJKL", "https://drive.google.com@evil.test/open?id=ABCDEFGHIJKL", "https://drive.google.com:444/open?id=ABCDEFGHIJKL", "https://drive.google.com/open?id=ABCDEFGHIJKL#x", "https://googleusercontent.com/open?id=ABCDEFGHIJKL"] {
            XCTAssertThrowsError(try DriveLinks.normalize(raw), raw)
        }
    }
    func testRejectUnsupportedAndAmbiguousLinks() {
        for raw in ["https://drive.google.com/drive/folders/ABCDEFGHIJKL", "https://docs.google.com/document/d/ABCDEFGHIJKL/edit", "https://drive.google.com/open?id=ABCDEFGHIJKL&id=MNOPQRSTUVWX", "https://drive.google.com/file/d/ABCDEFGHIJKL/view?id=MNOPQRSTUVWX", "https://drive.google.com/open?id=ABCDEFGHIJKL&resourcekey=a%2Fb"] {
            XCTAssertThrowsError(try DriveLinks.normalize(raw), raw)
        }
    }
    func testIPv4AndIPv6() {
        for ip in ["1.2.3.4", "2001:4860:4860::8888", "127.0.0.1"] { XCTAssertTrue(DriveLinks.validIP(ip)) }
        for ip in ["", "1.2.3.999", "google.com", "1.2.3.4:443", "::1\r\nHost:x", "01.2.3.4"] { XCTAssertFalse(DriveLinks.validIP(ip)) }
    }
    func testSafeNames() {
        XCTAssertEqual(DriveLinks.safeName("attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.pdf"), "文件.pdf")
        XCTAssertEqual(DriveLinks.safeName("attachment; filename=\"../../bad\\file.txt\""), "_.._bad_file.txt")
        XCTAssertEqual(DriveLinks.sanitizeName(".."), "gdrive-download.bin")
        XCTAssertLessThanOrEqual(DriveLinks.sanitizeName(String(repeating: "文", count: 200)).utf8.count, 180)
        XCTAssertFalse(DriveLinks.sanitizeName("a\u{202E}b").contains("\u{202E}"))
    }
    func testConfirmationPreservesIdentity() throws {
        let original = try DriveLinks.normalize("https://drive.google.com/open?id=ABCDEFGHIJKL&resourcekey=0_secret")
        let html = "<form id='download-form' action='https://drive.usercontent.google.com/download' method='get'><input name='id' value='ABCDEFGHIJKL'><input name='confirm' value='t'><input name='export' value='download'></form>"
        let result = try DriveConfirmation.target(html: html, base: original, original: original)
        XCTAssertTrue(result.absoluteString.contains("resourcekey=0_secret"))
    }
    func testRejectUnsafeConfirmation() throws {
        let original = try DriveLinks.normalize("https://drive.google.com/open?id=ABCDEFGHIJKL")
        for action in ["https://evil.test/download", "http://drive.google.com/download", "https://accounts.google.com/login"] {
            let html = "<form action='\(action)'><input name='id' value='ABCDEFGHIJKL'><input name='confirm' value='t'></form>"
            XCTAssertThrowsError(try DriveConfirmation.target(html: html, base: original, original: original))
        }
        for input in ["<input name='password' value='x'>", "<input name='id' value='ABCDEFGHIJKL'>", "<input name='resourcekey' value='different'>"] {
            let html = "<form action='/download'><input name='id' value='ABCDEFGHIJKL'><input name='confirm' value='t'>\(input)</form>"
            XCTAssertThrowsError(try DriveConfirmation.target(html: html, base: original, original: original))
        }
        XCTAssertThrowsError(try DriveConfirmation.target(html: String(repeating: "x", count: DriveConfirmation.limit + 1), base: original, original: original))
    }
}

final class DecoderTests: XCTestCase {
    private func decode(_ raw: String, limit: Int64 = 1000, stride: Int = 1) throws -> Data {
        var result = Data(); let parser = HTTPDecoder(limit: limit, sink: { result.append($0) })
        let bytes = Data(raw.utf8)
        for offset in Swift.stride(from: 0, to: bytes.count, by: stride) { try parser.feed(bytes.subdata(in: offset..<min(bytes.count, offset + stride))) }
        try parser.end(); XCTAssertTrue(parser.complete); return result
    }
    func testFragmentedFixedBody() throws { XCTAssertEqual(try decode("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"), Data("hello".utf8)) }
    func testChunkedAcrossEveryBoundary() throws {
        let raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;ext=yes\r\nhe\r\n3\r\nllo\r\n0\r\nX-Check: ok\r\n\r\n"
        for n in [1, 2, 3, 7, 64] { XCTAssertEqual(try decode(raw, stride: n), Data("hello".utf8)) }
    }
    func testEOFBody() throws { XCTAssertEqual(try decode("HTTP/1.0 200 OK\r\n\r\nhello"), Data("hello".utf8)) }
    func testTruncationRejected() {
        for raw in ["HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhell", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nab"] { XCTAssertThrowsError(try decode(raw)) }
    }
    func testInvalidFramingRejected() {
        for headers in ["Content-Length: -1", "Content-Length: 9223372036854775808", "Content-Length: 1\r\nContent-Length: 1", "Content-Length: 1\r\nTransfer-Encoding: chunked", "Transfer-Encoding: gzip", "Content-Encoding: gzip", "Content-Length : 1"] {
            XCTAssertThrowsError(try decode("HTTP/1.1 200 OK\r\n\(headers)\r\n\r\na"), headers)
        }
    }
    func testOversizeAndExtraBodyRejected() {
        XCTAssertThrowsError(try decode("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nab", limit: 1))
        XCTAssertThrowsError(try decode("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nab"))
        XCTAssertThrowsError(try decode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n0\r\n\r\n", limit: 1))
    }
    func testHeaderAndTrailerBounds() {
        XCTAssertThrowsError(try decode("HTTP/1.1 200 OK\r\nX: " + String(repeating: "a", count: 33_000)))
        XCTAssertThrowsError(try decode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nContent-Length: 9\r\n\r\n"))
    }
    func testSinkFailurePropagates() {
        let p = HTTPDecoder(limit: 10, sink: { _ in throw DriveError.message("disk full") })
        XCTAssertThrowsError(try p.feed(Data("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\na".utf8)))
        XCTAssertFalse(p.complete)
    }
}

final class CacheTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true) }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }
    func testStrongETagAndRange() throws {
        XCTAssertNil(RangeRules.strongETag("W/\"v1\"")); XCTAssertNil(RangeRules.strongETag("bad\r\nheader")); XCTAssertEqual(RangeRules.strongETag("\"v1\""), "\"v1\"")
        XCTAssertEqual(try RangeRules.total("bytes 0-0/99", first: 0, last: 0), 99)
        for header in ["bytes 1-1/99", "bytes 0-0/*", "bytes 0-0/0"] { XCTAssertThrowsError(try RangeRules.total(header, first: 0, last: 0)) }
        let h = HTTPHead(status: 200, headers: ["etag":"\"v1\"", "content-range":"bytes 0-0/99"])
        XCTAssertThrowsError(try RangeRules.validate(h, first: 0, last: 0, size: 99, etag: "\"v1\""))
        let changed = HTTPHead(status: 206, headers: ["etag":"\"v2\"", "content-range":"bytes 0-0/99"])
        XCTAssertThrowsError(try RangeRules.validate(changed, first: 0, last: 0, size: 99, etag: "\"v1\""))
    }
    func testCacheResumeAssembleAndTamper() async throws {
        let folder = root.appendingPathComponent("cache")
        let store = try ChunkStore(folder: folder, etag: "\"v1\"", size: 5, chunkSize: 3)
        let a = root.appendingPathComponent("a"); try Data("abc".utf8).write(to: a); try await store.commit(index: 0, source: a)
        let b = root.appendingPathComponent("b"); try Data("de".utf8).write(to: b); try await store.commit(index: 1, source: b)
        let resumed = try ChunkStore(folder: folder, etag: "\"v1\"", size: 5, chunkSize: 3)
        let valid = try await resumed.verifiedChunks(); XCTAssertEqual(valid, Set([0,1]))
        let out = root.appendingPathComponent("out"); try await resumed.assemble(to: out); XCTAssertEqual(try Data(contentsOf: out), Data("abcde".utf8))
        try Data("xyz".utf8).write(to: folder.appendingPathComponent("0.chunk"))
        let after = try await resumed.verifiedChunks(); XCTAssertEqual(after, Set([1]))
    }
    func testChangedValidatorClearsOldChunks() async throws {
        let folder = root.appendingPathComponent("cache")
        let old = try ChunkStore(folder: folder, etag: "\"v1\"", size: 3, chunkSize: 3)
        let a = root.appendingPathComponent("a"); try Data("abc".utf8).write(to: a); try await old.commit(index: 0, source: a)
        let changed = try ChunkStore(folder: folder, etag: "\"v2\"", size: 3, chunkSize: 3)
        let valid = try await changed.verifiedChunks(); XCTAssertTrue(valid.isEmpty)
    }
    func testIncompleteChunkAndPublishCollision() async throws {
        let store = try ChunkStore(folder: root.appendingPathComponent("cache"), etag: "\"v1\"", size: 3, chunkSize: 3)
        let a = root.appendingPathComponent("a"); try Data("ab".utf8).write(to: a)
        do { try await store.commit(index: 0, source: a); XCTFail("incomplete chunk accepted") } catch {}
        let dest = root.appendingPathComponent("dest"); try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        let old = dest.appendingPathComponent("a.txt"); try Data("old".utf8).write(to: old)
        let saved = try DriveDownloader.publish(a, name: "a.txt", destination: dest)
        XCTAssertNotEqual(saved, old); XCTAssertEqual(try String(contentsOf: old), "old")
    }
}

private final class MockFetcher: HTTPFetching, @unchecked Sendable {
    struct Response { let status: Int; let headers: [String:String]; let body: Data }
    let handler: @Sendable (FetchRequest) throws -> Response
    init(_ handler: @escaping @Sendable (FetchRequest) throws -> Response) { self.handler = handler }
    func fetch(_ request: FetchRequest, inspect: @escaping (HTTPHead) throws -> Int64, progress: @escaping @Sendable (Int64, Int64?) -> Void) async throws -> HTTPResult {
        try Task.checkCancellation(); let response = try handler(request)
        let head = HTTPHead(status: response.status, headers: response.headers)
        let limit = min(request.limit, try inspect(head)); guard response.body.count <= limit else { throw DriveError.message("oversize") }
        let file = request.directory.appendingPathComponent(UUID().uuidString)
        try response.body.write(to: file); progress(Int64(response.body.count), head.length)
        return HTTPResult(head: head, file: file, bytes: Int64(response.body.count))
    }
}
final class DownloaderTests: XCTestCase {
    private let link = "https://drive.google.com/open?id=ABCDEFGHIJKL"
    private var root: URL!
    override func setUpWithError() throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true) }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }
    private func run(_ fetcher: MockFetcher) async throws -> URL {
        try await DriveDownloader(client: fetcher).download(link: link, destination: root.appendingPathComponent("files"), cache: root.appendingPathComponent("cache"), progress: { _ in })
    }
    func testServerIgnoringRangePublishesOneWholeFile() async throws {
        let file = try await run(MockFetcher { _ in .init(status: 200, headers: ["content-length":"5", "content-disposition":"attachment; filename=ok.txt"], body: Data("hello".utf8)) })
        XCTAssertEqual(file.lastPathComponent, "ok.txt"); XCTAssertEqual(try String(contentsOf: file), "hello")
    }
    func testNoStrongETagUsesFreshFullRequest() async throws {
        let file = try await run(MockFetcher { request in
            if request.range != nil { return .init(status: 206, headers: ["content-range":"bytes 0-0/5"], body: Data("h".utf8)) }
            return .init(status: 200, headers: ["content-length":"5"], body: Data("hello".utf8))
        })
        XCTAssertEqual(try String(contentsOf: file), "hello")
    }
    func testStrongValidatorDownloadsRange() async throws {
        let file = try await run(MockFetcher { request in
            let probe = request.range == "bytes=0-0"
            return .init(status: 206, headers: ["content-range":probe ? "bytes 0-0/5" : "bytes 0-4/5", "etag":"\"v1\""], body: Data((probe ? "h" : "hello").utf8))
        })
        XCTAssertEqual(try String(contentsOf: file), "hello")
    }
    func testMultipleChunksWithAllConnectionChoices() async throws {
        let chunk = DriveDownloader.chunkSize
        let size = chunk * 2 + 37
        for connections in [1, 4, 8] {
            let fetcher = MockFetcher { request in
                let raw = request.range!.dropFirst("bytes=".count).split(separator: "-")
                let first = Int64(raw[0])!; let last = Int64(raw[1])!
                let index = first / chunk
                return .init(status: 206, headers: ["content-range":"bytes \(first)-\(last)/\(size)", "etag":"\"version\""], body: Data(repeating: UInt8(65 + index), count: Int(last - first + 1)))
            }
            let file = try await DriveDownloader(client: fetcher).download(link: link, connections: connections, destination: root.appendingPathComponent("files"), cache: root.appendingPathComponent("cache"), progress: { _ in })
            let data = try Data(contentsOf: file)
            XCTAssertEqual(data.count, Int(size)); XCTAssertEqual(data[0], 65)
            XCTAssertEqual(data[Int(chunk)], 66); XCTAssertEqual(data[Int(chunk * 2)], 67)
        }
    }
    func testUnsafeRedirectAndQuotaPublishNothing() async throws {
        for status in [302,403,404,429,503] {
            do { _ = try await run(MockFetcher { _ in .init(status: status, headers: ["location":"https://evil.test/file"], body: Data()) }); XCTFail("unsafe response accepted") } catch {}
        }
        let list = try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("files").path); XCTAssertTrue(list.isEmpty)
    }
    func testChangedETagPublishesNothing() async throws {
        do {
            _ = try await run(MockFetcher { request in
                let probe = request.range == "bytes=0-0"
                return .init(status: 206, headers: ["content-range":probe ? "bytes 0-0/5" : "bytes 0-4/5", "etag":probe ? "\"v1\"" : "\"v2\""], body: Data((probe ? "h" : "hello").utf8))
            }); XCTFail("changed validator accepted")
        } catch {}
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("files").path).isEmpty)
    }
    func testCancellationBeforeRequest() async throws {
        let downloader = DriveDownloader(client: MockFetcher { _ in XCTFail("cancelled request reached network"); throw CancellationError() })
        let task = Task { try await downloader.download(link: link, destination: root, cache: root, progress: { _ in }) }
        task.cancel()
        do { _ = try await task.value; XCTFail("cancel not propagated") } catch is CancellationError {} catch { XCTFail("\(error)") }
    }
}
