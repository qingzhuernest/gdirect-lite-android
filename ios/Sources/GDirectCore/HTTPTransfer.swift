import Foundation
import Network
import Security

struct HTTPResult: Sendable {
    let head: HTTPHead
    let file: URL
    let bytes: Int64
}

enum TLSIdentity {
    /// Called on the original system trust object. No alternate anchors or exceptions.
    static func evaluate(_ trust: SecTrust, hostname: String) -> Bool {
        guard SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, hostname as CFString)) == errSecSuccess else { return false }
        return SecTrustEvaluateWithError(trust, nil)
    }

    static func options(hostname: String, queue: DispatchQueue) -> NWProtocolTLS.Options {
        let options = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_tls_server_name(options.securityProtocolOptions, hostname)
        sec_protocol_options_add_tls_application_protocol(options.securityProtocolOptions, "http/1.1")
        sec_protocol_options_set_verify_block(options.securityProtocolOptions, { _, trust, complete in
            complete(evaluate(sec_trust_copy_ref(trust).takeRetainedValue(), hostname: hostname))
        }, queue)
        return options
    }
}

/// One-shot transport. All mutable state, including cancellation-before-start, lives
/// on one serial queue. The URL supplies Host/SNI; only the TCP endpoint uses IP.
final class HTTPTransfer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.gdirect.https." + UUID().uuidString)
    private var connection: NWConnection?
    private var continuation: CheckedContinuation<HTTPResult, Error>?
    private var handle: FileHandle?
    private var output: URL?
    private var timer: DispatchSourceTimer?
    private var deadline: DispatchSourceTimer?
    private var started = false
    private var cancelled = false
    private var finished = false
    private var decoder: HTTPDecoder?

    func cancel() {
        queue.async {
            self.cancelled = true
            if self.started { self.finish(.failure(CancellationError())) }
        }
    }

    func request(_ url: URL, ip: String?, directory: URL, range: String? = nil, etag: String? = nil,
                 limit: Int64, idleTimeout: TimeInterval = 30, totalTimeout: TimeInterval = 86_400,
                 port: UInt16 = 443,
                 inspect: @escaping (HTTPHead) throws -> Int64 = { _ in Int64.max },
                 progress: @escaping @Sendable (Int64, Int64?) -> Void = { _, _ in }) async throws -> HTTPResult {
        guard url.scheme == "https", let host = url.host, url.user == nil, url.password == nil,
              ip.map(DriveLinks.validIP) ?? true, let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              range.map({ $0.range(of: "^bytes=[0-9]+-[0-9]+$", options: .regularExpression) != nil }) ?? true,
              etag == nil || RangeRules.strongETag(etag) != nil,
              let endpointPort = NWEndpoint.Port(rawValue: port) else { throw DriveError.message("HTTPS 请求参数无效。") }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    guard !self.started else { continuation.resume(throwing: DriveError.message("连接不可重复使用。")); return }
                    self.started = true; self.continuation = continuation
                    guard !self.cancelled else { self.finish(.failure(CancellationError())); return }
                    do {
                        let output = directory.appendingPathComponent(UUID().uuidString + ".incoming")
                        self.output = output
                        guard FileManager.default.createFile(atPath: output.path, contents: nil) else { throw DriveError.message("无法创建下载文件，请检查存储空间。") }
                        self.handle = try FileHandle(forWritingTo: output)
                        self.decoder = HTTPDecoder(limit: limit, onHead: inspect, sink: { data in
                            guard let handle = self.handle else { throw DriveError.message("文件已关闭。") }
                            try handle.write(contentsOf: data)
                        })
                        let parameters = NWParameters(tls: TLSIdentity.options(hostname: host, queue: self.queue), tcp: NWProtocolTCP.Options())
                        let connection = NWConnection(host: NWEndpoint.Host(ip ?? host), port: endpointPort, using: parameters)
                        self.connection = connection
                        let timer = DispatchSource.makeTimerSource(queue: self.queue)
                        timer.schedule(deadline: .now() + idleTimeout)
                        timer.setEventHandler { self.finish(.failure(DriveError.message("网络空闲超时，请检查网络或 IP。"))) }
                        self.timer = timer; timer.resume()
                        let deadline = DispatchSource.makeTimerSource(queue: self.queue)
                        deadline.schedule(deadline: .now() + totalTimeout)
                        deadline.setEventHandler { self.finish(.failure(DriveError.message("下载超过最长时限。"))) }
                        self.deadline = deadline; deadline.resume()
                        var path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
                        if let query = components.percentEncodedQuery { path += "?" + query }
                        guard !path.utf8.contains(where: { $0 < 32 || $0 == 127 }),
                              !host.utf8.contains(where: { $0 <= 32 || $0 == 127 }) else { throw DriveError.message("请求地址无效。") }
                        var request = "GET \(path) HTTP/1.1\r\nHost: \(host)\r\nUser-Agent: GDirectLite/0.1 iOS\r\nAccept-Encoding: identity\r\nConnection: close\r\n"
                        if let range { request += "Range: \(range)\r\n" }
                        if let etag { request += "If-Range: \(etag)\r\n" }
                        request += "\r\n"
                        let requestData = Data(request.utf8)
                        connection.stateUpdateHandler = { state in
                            guard !self.finished else { return }
                            switch state {
                            case .ready:
                                connection.send(content: requestData, completion: .contentProcessed { error in
                                    if let error { self.finish(.failure(error)) }
                                    else { self.receive(idleTimeout: idleTimeout, progress: progress) }
                                })
                            case .failed:
                                self.finish(.failure(DriveError.message("HTTPS 连接失败：网络不可达，或证书链/主机名验证未通过。")))
                            default: break
                            }
                        }
                        connection.start(queue: self.queue)
                    } catch { self.finish(.failure(error)) }
                }
            }
        } onCancel: { self.cancel() }
    }

    private func receive(idleTimeout: TimeInterval, progress: @escaping @Sendable (Int64, Int64?) -> Void) {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, complete, error in
            guard !self.finished, let decoder = self.decoder else { return }
            do {
                if let error { throw error }
                if let data, !data.isEmpty {
                    self.timer?.schedule(deadline: .now() + idleTimeout)
                    try decoder.feed(data)
                    progress(decoder.bytes, decoder.head?.length)
                }
                if complete { try decoder.end() }
                if decoder.complete {
                    guard let output = self.output, let head = decoder.head else { throw DriveError.message("响应无效。") }
                    try self.handle?.synchronize()
                    try self.handle?.close(); self.handle = nil
                    self.finish(.success(HTTPResult(head: head, file: output, bytes: decoder.bytes)))
                } else { self.receive(idleTimeout: idleTimeout, progress: progress) }
            } catch { self.finish(.failure(error)) }
        }
    }

    private func finish(_ result: Result<HTTPResult, Error>) {
        guard !finished else { return }; finished = true
        timer?.cancel(); deadline?.cancel()
        connection?.stateUpdateHandler = nil; connection?.cancel(); connection = nil
        try? handle?.close(); handle = nil; decoder = nil
        if case .failure = result, let output { try? FileManager.default.removeItem(at: output) }
        continuation?.resume(with: result); continuation = nil
    }
}
