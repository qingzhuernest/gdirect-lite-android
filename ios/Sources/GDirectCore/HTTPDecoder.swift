import Foundation

struct HTTPHead: Sendable {
    let status: Int
    let headers: [String: String]
    var length: Int64? { headers["content-length"].flatMap(Int64.init) }
    var isPage: Bool {
        let type = headers["content-type", default: ""].lowercased()
        return (type.contains("text/html") || type.contains("application/xhtml"))
            && !headers["content-disposition", default: ""].lowercased().hasPrefix("attachment")
    }
}

/// Incremental HTTP/1.1 framing. Only decoded payload reaches the disk sink.
/// Bounds apply even when delimiters and trailers arrive one byte at a time.
final class HTTPDecoder {
    private enum State { case head, fixed(Int64), untilEOF, chunkSize, chunk(Int64), chunkEnd, trailers, done }
    private var state: State = .head
    private var buffer = Data()
    private var trailerBytes = 0
    private(set) var head: HTTPHead?
    private(set) var bytes: Int64 = 0
    private(set) var limit: Int64
    var complete: Bool { if case .done = state { return true }; return false }
    let onHead: (HTTPHead) throws -> Int64
    let sink: (Data) throws -> Void

    init(limit: Int64, onHead: @escaping (HTTPHead) throws -> Int64 = { _ in Int64.max }, sink: @escaping (Data) throws -> Void) {
        self.limit = limit; self.onHead = onHead; self.sink = sink
    }

    func feed(_ data: Data) throws {
        guard !complete || data.isEmpty else { throw failure("响应包含多余数据") }
        buffer.append(data)
        while true {
            switch state {
            case .head:
                guard let range = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                    guard buffer.count <= 32_768 else { throw failure("响应头过大") }; return
                }
                guard range.lowerBound - buffer.startIndex <= 32_768 else { throw failure("响应头过大") }
                let text = String(decoding: buffer[..<range.lowerBound], as: UTF8.self)
                let lines = text.components(separatedBy: "\r\n")
                let parts = lines[0].split(separator: " ", maxSplits: 2)
                guard parts.count >= 2, ["HTTP/1.1", "HTTP/1.0"].contains(String(parts[0])),
                      parts[1].count == 3, let status = Int(parts[1]), (200...599).contains(status) else { throw failure("状态行无效") }
                var headers: [String: String] = [:]
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of: ":") else { throw failure("响应头无效") }
                    let name = String(line[..<colon]).lowercased()
                    let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                    guard !name.isEmpty, name.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || "!#$%&'*+-.^_`|~".utf8.contains($0) }),
                          !value.utf8.contains(where: { ($0 < 32 && $0 != 9) || $0 == 127 }) else { throw failure("响应头字符无效") }
                    // Repeated framing, validators, redirects, and type headers are ambiguous.
                    if headers[name] != nil && name != "set-cookie" { throw failure("响应头重复") }
                    headers[name] = value
                }
                guard headers["content-encoding"] == nil || headers["content-encoding"]?.lowercased() == "identity" else { throw failure("不支持压缩响应") }
                let transfer = headers["transfer-encoding"]?.lowercased()
                guard transfer == nil || transfer == "chunked", transfer == nil || headers["content-length"] == nil else { throw failure("响应长度冲突") }
                var length: Int64?
                if let value = headers["content-length"] {
                    guard !value.isEmpty, value.utf8.allSatisfy({ (48...57).contains($0) }), let n = Int64(value) else { throw failure("响应长度无效") }
                    length = n
                }
                let head = HTTPHead(status: status, headers: headers)
                self.head = head
                limit = min(limit, try onHead(head))
                guard limit >= 0, length.map({ $0 <= limit }) ?? true else { throw failure("响应超过大小限制") }
                buffer.removeSubrange(..<range.upperBound)
                if status == 204 || status == 304 {
                    guard transfer == nil, length == nil || length == 0 else { throw failure("无内容响应携带数据") }
                    state = .done
                } else if transfer == "chunked" { state = .chunkSize }
                else if let length { state = length == 0 ? .done : .fixed(length) }
                else { state = .untilEOF }
            case .fixed(let remaining):
                guard !buffer.isEmpty else { return }
                let count = min(Int64(buffer.count), remaining)
                try emit(Int(count))
                state = count == remaining ? .done : .fixed(remaining - count)
            case .untilEOF:
                if !buffer.isEmpty { try emit(buffer.count) }; return
            case .chunkSize:
                guard let line = try takeLine(limit: 1024) else { return }
                let size = line.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)[0]
                guard !size.isEmpty, size.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }),
                      let count = Int64(size, radix: 16), count <= limit - bytes else { throw failure("数据块长度无效或超过限制") }
                state = count == 0 ? .trailers : .chunk(count)
            case .chunk(let remaining):
                guard !buffer.isEmpty else { return }
                let count = min(Int64(buffer.count), remaining)
                try emit(Int(count)); state = count == remaining ? .chunkEnd : .chunk(remaining - count)
            case .chunkEnd:
                guard buffer.count >= 2 else { return }
                guard buffer.prefix(2) == Data("\r\n".utf8) else { throw failure("数据块结尾无效") }
                buffer.removeFirst(2); state = .chunkSize
            case .trailers:
                guard let line = try takeLine(limit: 8192) else { return }
                trailerBytes += line.utf8.count + 2
                guard trailerBytes <= 16_384 else { throw failure("trailer 过大") }
                if line.isEmpty { state = .done }
                else {
                    guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { throw failure("trailer 无效") }
                    let key = line[..<colon].lowercased()
                    guard !["content-length", "transfer-encoding", "content-range", "etag", "content-encoding"].contains(key) else { throw failure("trailer 修改了响应属性") }
                }
            case .done:
                guard buffer.isEmpty else { throw failure("响应包含多余数据") }; return
            }
        }
    }

    func end() throws {
        if case .untilEOF = state { state = .done }
        guard complete else { throw failure("下载中断，响应不完整") }
    }

    private func takeLine(limit: Int) throws -> String? {
        guard let range = buffer.range(of: Data("\r\n".utf8)) else {
            guard buffer.count <= limit else { throw failure("协议行过长") }; return nil
        }
        guard range.lowerBound - buffer.startIndex <= limit else { throw failure("协议行过长") }
        let line = String(decoding: buffer[..<range.lowerBound], as: UTF8.self)
        buffer.removeSubrange(..<range.upperBound); return line
    }

    private func emit(_ count: Int) throws {
        guard Int64(count) <= limit - bytes else { throw failure("响应超过大小限制") }
        let data = Data(buffer.prefix(count))
        try sink(data); bytes += Int64(count); buffer.removeFirst(count)
    }

    private func failure(_ text: String) -> DriveError { .message(text + "。") }
}

public enum RangeRules {
    public static func strongETag(_ value: String?) -> String? {
        guard let value, value.utf8.count <= 512, value.first == "\"", value.last == "\"", value.count >= 2,
              value.dropFirst().dropLast().utf8.allSatisfy({ $0 == 33 || (35...126).contains($0) }) else { return nil }
        return value
    }

    public static func total(_ header: String?, first: Int64, last: Int64) throws -> Int64 {
        guard let header, let parts = captures("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$", in: header), parts.count == 3,
              let start = Int64(parts[0]), let end = Int64(parts[1]), let total = Int64(parts[2]),
              start == first, end == last, first >= 0, last >= first, total > end else {
            throw DriveError.message("Content-Range 无效，已停止拼接文件。")
        }
        return total
    }

    static func validate(_ head: HTTPHead, first: Int64, last: Int64, size: Int64, etag: String) throws {
        guard head.status == 206, strongETag(etag) != nil, head.headers["etag"] == etag,
              try total(head.headers["content-range"], first: first, last: last) == size,
              head.length == nil || head.length == last - first + 1, !head.isPage else {
            throw DriveError.message("服务器未保持 Range 或强 ETag；为避免混合版本已停止下载。")
        }
    }
}
