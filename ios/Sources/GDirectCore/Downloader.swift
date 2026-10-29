import Foundation
import CryptoKit

public struct DownloadUpdate: Sendable {
    public let completed: Int64
    public let total: Int64?
    public let message: String
    public let connections: Int
}

struct FetchRequest: Sendable {
    let url: URL
    let ip: String?
    let directory: URL
    var range: String? = nil
    var etag: String? = nil
    var limit: Int64
}

protocol HTTPFetching: Sendable {
    func fetch(_ request: FetchRequest, inspect: @escaping (HTTPHead) throws -> Int64,
               progress: @escaping @Sendable (Int64, Int64?) -> Void) async throws -> HTTPResult
}

struct NetworkFetcher: HTTPFetching {
    func fetch(_ request: FetchRequest, inspect: @escaping (HTTPHead) throws -> Int64,
               progress: @escaping @Sendable (Int64, Int64?) -> Void) async throws -> HTTPResult {
        try await HTTPTransfer().request(request.url, ip: request.ip, directory: request.directory,
                                         range: request.range, etag: request.etag, limit: request.limit,
                                         inspect: inspect, progress: progress)
    }
}

/// One foreground transfer at a time. No authentication, cookies or background session.
public actor DriveDownloader {
    public static let maximumSize: Int64 = 50 * 1024 * 1024 * 1024
    static let chunkSize: Int64 = 4 * 1024 * 1024
    private let client: any HTTPFetching
    private var busy = false
    public init() { client = NetworkFetcher() }
    init(client: any HTTPFetching) { self.client = client }

    public func download(link: String, ip: String? = nil, connections: Int = 4,
                         destination: URL, cache: URL,
                         progress: @escaping @Sendable (DownloadUpdate) -> Void) async throws -> URL {
        guard !busy else { throw DriveError.message("请等待当前传输结束。") }
        guard [1, 4, 8].contains(connections), ip.map(DriveLinks.validIP) ?? true else { throw DriveError.message("连接数或 IP 无效。") }
        busy = true
        defer { busy = false }
        try Task.checkCancellation()
        let original = try DriveLinks.normalize(link)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let incoming = cache.appendingPathComponent("incoming-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: incoming) }
        progress(DownloadUpdate(completed: 0, total: nil, message: "正在验证公开文件与 HTTPS…", connections: 1))
        var url = original
        var confirmations = 0
        var redirects = 0
        var seen = Set<String>()
        while true {
            try Task.checkCancellation()
            guard seen.insert(url.absoluteString).inserted else { throw DriveError.message("下载确认或跳转循环。") }
            let request = FetchRequest(url: url, ip: ip, directory: incoming, range: "bytes=0-0", limit: Self.maximumSize)
            let meter = ProgressMeter(total: nil, connections: 1, message: "正在下载（单连接）", callback: progress)
            let (response, finalURL) = try await followed(request, redirects: &redirects, inspect: { head in
                if head.status == 206 {
                    let total = try RangeRules.total(head.headers["content-range"], first: 0, last: 0)
                    guard total <= Self.maximumSize else { throw DriveError.message("文件超过 50 GiB 上限。") }
                    return 1
                }
                guard head.status == 200 else { throw Self.statusError(head.status) }
                return head.isPage ? Int64(DriveConfirmation.limit) : Self.maximumSize
            }, progress: { bytes, total in meter.record(index: 0, bytes: bytes, total: total) })
            defer { try? FileManager.default.removeItem(at: response.file) }
            if response.head.isPage {
                confirmations += 1
                guard confirmations <= 2 else { throw DriveError.message("确认页次数超过限制。") }
                let data = try Data(contentsOf: response.file)
                guard let html = String(data: data, encoding: .utf8) else { throw DriveError.message("确认页编码不受支持。") }
                url = try DriveConfirmation.target(html: html, base: finalURL, original: original)
                continue
            }
            let name = DriveLinks.safeName(response.head.headers["content-disposition"])
            if response.head.status == 200 {
                // A server ignoring the probe's Range has already sent the entire body.
                // Never append this response to cached bytes.
                try Task.checkCancellation()
                let saved = try Self.publish(response.file, name: name, destination: destination)
                progress(DownloadUpdate(completed: response.bytes, total: response.bytes, message: "下载完成", connections: 1))
                return saved
            }
            let size = try RangeRules.total(response.head.headers["content-range"], first: 0, last: 0)
            guard response.bytes == 1 else { throw DriveError.message("Range 探测响应长度错误。") }
            guard let etag = RangeRules.strongETag(response.head.headers["etag"]) else {
                // Without a strong validator, use one fresh full GET. Do not resume.
                var full = FetchRequest(url: finalURL, ip: ip, directory: incoming, limit: Self.maximumSize)
                full.range = nil
                let meter = ProgressMeter(total: size, connections: 1, message: "单连接下载（无强 ETag，不支持续传）", callback: progress)
                let (result, _) = try await followed(full, redirects: &redirects, inspect: { head in
                    guard head.status == 200, !head.isPage else { throw Self.statusError(head.status) }
                    guard head.length == nil || head.length == size else { throw DriveError.message("远端文件大小已变化，请重试。") }
                    return size
                }, progress: { bytes, _ in meter.record(index: 0, bytes: bytes) })
                defer { try? FileManager.default.removeItem(at: result.file) }
                guard result.bytes == size else { throw DriveError.message("下载大小与探测不一致。") }
                try Task.checkCancellation()
                let saved = try Self.publish(result.file, name: name, destination: destination)
                progress(DownloadUpdate(completed: size, total: size, message: "下载完成", connections: 1))
                return saved
            }
            return try await ranged(original: original, finalURL: finalURL, ip: ip, etag: etag, size: size,
                                    name: name, connections: connections, destination: destination,
                                    cache: cache, incoming: incoming, progress: progress)
        }
    }

    private func ranged(original: URL, finalURL: URL, ip: String?, etag: String, size: Int64, name: String,
                        connections: Int, destination: URL, cache: URL, incoming: URL,
                        progress: @escaping @Sendable (DownloadUpdate) -> Void) async throws -> URL {
        let key = SHA256.hash(data: Data(original.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        let store = try ChunkStore(folder: cache.appendingPathComponent(key), etag: etag, size: size, chunkSize: Self.chunkSize)
        let completed = try await store.verifiedChunks()
        let count = Int((size - 1) / Self.chunkSize + 1)
        let meter = ProgressMeter(total: size, connections: connections, message: "Range 下载 · 强 ETag 保护，可重试续传", callback: progress)
        for index in completed { meter.record(index: index, bytes: min(Self.chunkSize, size - Int64(index) * Self.chunkSize)) }
        let pending = (0..<count).filter { !completed.contains($0) }
        let client = self.client
        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = 0
            func enqueue(_ index: Int) {
                group.addTask {
                    try Task.checkCancellation()
                    let first = Int64(index) * Self.chunkSize
                    let last = min(size - 1, first + Self.chunkSize - 1)
                    let request = FetchRequest(url: finalURL, ip: ip, directory: incoming,
                                               range: "bytes=\(first)-\(last)", etag: etag, limit: last - first + 1)
                    // Chunk redirects are deliberately refused. A fresh user retry re-probes
                    // expiring signed URLs before reusing any completed chunk.
                    let result = try await client.fetch(request, inspect: { head in
                        try RangeRules.validate(head, first: first, last: last, size: size, etag: etag)
                        return last - first + 1
                    }, progress: { bytes, _ in meter.record(index: index, bytes: bytes) })
                    defer { try? FileManager.default.removeItem(at: result.file) }
                    try RangeRules.validate(result.head, first: first, last: last, size: size, etag: etag)
                    guard result.bytes == last - first + 1 else { throw DriveError.message("分块长度不完整。") }
                    try Task.checkCancellation()
                    try await store.commit(index: index, source: result.file)
                    meter.record(index: index, bytes: result.bytes, force: true)
                }
            }
            for _ in 0..<min(connections, pending.count) { enqueue(pending[next]); next += 1 }
            do {
                while try await group.next() != nil {
                    if next < pending.count { enqueue(pending[next]); next += 1 }
                }
            } catch { group.cancelAll(); throw error }
        }
        try Task.checkCancellation()
        progress(DownloadUpdate(completed: size, total: size, message: "正在校验并保存…", connections: connections))
        let assembled = incoming.appendingPathComponent(UUID().uuidString + ".assembled")
        try await store.assemble(to: assembled)
        try Task.checkCancellation()
        let saved = try Self.publish(assembled, name: name, destination: destination)
        try? await store.remove()
        progress(DownloadUpdate(completed: size, total: size, message: "下载完成", connections: connections))
        return saved
    }

    private func followed(_ initial: FetchRequest, redirects: inout Int,
                          inspect: @escaping (HTTPHead) throws -> Int64,
                          progress: @escaping @Sendable (Int64, Int64?) -> Void) async throws -> (HTTPResult, URL) {
        var request = initial
        while true {
            try Task.checkCancellation()
            guard DriveLinks.allowed(request.url) else { throw DriveError.message("已阻止非 Google HTTPS 目标。") }
            let result = try await client.fetch(request, inspect: { head in
                if Self.isRedirect(head.status) { return 65_536 }
                return try inspect(head)
            }, progress: progress)
            if Self.isRedirect(result.head.status) {
                defer { try? FileManager.default.removeItem(at: result.file) }
                redirects += 1
                guard redirects <= 6, let location = result.head.headers["location"],
                      let url = URL(string: location, relativeTo: request.url)?.absoluteURL, DriveLinks.allowed(url) else {
                    throw DriveError.message("跳转次数过多或目标不是安全的 Google HTTPS 地址。")
                }
                request = FetchRequest(url: url, ip: request.ip, directory: request.directory,
                                       range: request.range, etag: request.etag, limit: request.limit)
            } else { return (result, request.url) }
        }
    }

    private static func isRedirect(_ code: Int) -> Bool { [301, 302, 303, 307, 308].contains(code) }
    private static func statusError(_ code: Int) -> DriveError {
        let detail: String
        switch code {
        case 401, 403: detail = "文件未公开、禁止下载或已超出配额；请在浏览器核对共享权限。"
        case 404: detail = "文件不存在或无权访问。"
        case 429: detail = "请求过多或配额受限，请稍后重试。"
        case 500...599: detail = "Google 服务暂时不可用，请稍后重试。"
        default: detail = "未返回可下载的文件，请检查公开链接。"
        }
        return .message("HTTP \(code)：" + detail)
    }

    static func publish(_ source: URL, name: String, destination: URL) throws -> URL {
        let safe = DriveLinks.sanitizeName(name)
        var target = destination.appendingPathComponent(safe)
        if FileManager.default.fileExists(atPath: target.path) {
            let path = safe as NSString
            let suffix = path.pathExtension
            let stem = String(path.deletingPathExtension.prefix(60))
            target = destination.appendingPathComponent(stem + "-" + UUID().uuidString.prefix(8) + (suffix.isEmpty ? "" : "." + suffix))
        }
        // Same-volume atomic move. Never replace a user's existing download.
        try FileManager.default.moveItem(at: source, to: target)
        return target
    }

    public func testConnection(ip: String? = nil) async throws -> String {
        guard !busy else { throw DriveError.message("传输期间请勿启动网络检测。") }
        guard ip.map(DriveLinks.validIP) ?? true else { throw DriveError.message("请输入 IPv4 或 IPv6 地址。") }
        busy = true
        defer { busy = false }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        guard let url = URL(string: "https://drive.google.com/robots.txt") else { throw DriveError.message("检测地址无效。") }
        let started = Date()
        let result = try await client.fetch(FetchRequest(url: url, ip: ip, directory: folder, limit: 262_144), inspect: { head in
            guard head.status == 200 else { throw Self.statusError(head.status) }; return 262_144
        }, progress: { _, _ in })
        guard result.head.status == 200 else { throw Self.statusError(result.head.status) }
        return "HTTPS 验证通过 · \(Int(Date().timeIntervalSince(started) * 1000)) ms。仅证明 drive.google.com 当前可达；下载域名仍需单独验证。"
    }
}

/// Synchronous, throttled accounting avoids spawning a Task per received buffer.
private final class ProgressMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [Int: Int64] = [:]
    private var last = Date.distantPast
    private var total: Int64?
    private let connections: Int
    private let message: String
    private let callback: @Sendable (DownloadUpdate) -> Void
    init(total: Int64?, connections: Int, message: String, callback: @escaping @Sendable (DownloadUpdate) -> Void) {
        self.total = total; self.connections = connections; self.message = message; self.callback = callback
    }
    func record(index: Int, bytes: Int64, total: Int64? = nil, force: Bool = false) {
        lock.lock()
        counts[index] = bytes
        if self.total == nil { self.total = total }
        let now = Date()
        guard force || now.timeIntervalSince(last) >= 0.15 else { lock.unlock(); return }
        last = now
        let update = DownloadUpdate(completed: counts.values.reduce(0, +), total: self.total, message: message, connections: connections)
        lock.unlock()
        callback(update)
    }
}
