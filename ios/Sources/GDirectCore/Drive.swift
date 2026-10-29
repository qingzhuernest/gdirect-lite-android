import Foundation
import Darwin

public enum DriveError: LocalizedError, Equatable {
    case message(String)
    public var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

public enum DriveLinks {
    public static func allowed(_ url: URL) -> Bool {
        guard url.absoluteString.utf8.count <= 16_384,
              url.scheme?.lowercased() == "https", let host = url.host?.lowercased(),
              url.user == nil, url.password == nil, url.fragment == nil,
              url.port == nil || url.port == 443,
              !url.absoluteString.contains("\\"),
              !url.absoluteString.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else { return false }
        return ["drive.google.com", "drive.usercontent.google.com", "www.googleapis.com", "content.googleapis.com", "drive.googleapis.com"].contains(host)
            || host.hasSuffix(".googleusercontent.com")
    }

    public static func normalize(_ text: String) throws -> URL {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.utf8.count <= 8192, let url = URL(string: text), allowed(url),
              ["drive.google.com", "drive.usercontent.google.com"].contains(url.host?.lowercased() ?? ""),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw DriveError.message("请输入 HTTPS Google Drive 公开单文件链接。")
        }
        let query = try uniqueQuery(components.queryItems ?? [])
        let pathID = captures("^/file/d/([A-Za-z0-9_-]{10,200})(?:/view|/edit|/preview)?/?$", in: url.path)?.first
        let queryID = ["/open", "/uc", "/download"].contains(url.path) ? query["id"] : nil
        guard let id = pathID ?? queryID, token(id, min: 10), query["id"] == nil || query["id"] == id else {
            throw DriveError.message("无法识别文件；不支持文件夹或 Google 文档导出链接。")
        }
        if let key = query["resourcekey"], !token(key) { throw DriveError.message("resourcekey 无效。") }
        var target = URLComponents()
        target.scheme = "https"; target.host = "drive.usercontent.google.com"; target.path = "/download"
        target.queryItems = [URLQueryItem(name: "id", value: id), URLQueryItem(name: "export", value: "download")]
        if let key = query["resourcekey"] { target.queryItems?.append(URLQueryItem(name: "resourcekey", value: key)) }
        guard let result = target.url else { throw DriveError.message("链接无效。") }
        return result
    }

    public static func validIP(_ ip: String) -> Bool {
        if !ip.contains(":") {
            let parts = ip.split(separator: ".", omittingEmptySubsequences: false)
            guard parts.count == 4, parts.allSatisfy({ part in
                !part.isEmpty && (part == "0" || part.first != "0") && part.utf8.allSatisfy { (48...57).contains($0) }
            }) else { return false }
        }
        var v4 = in_addr(); var v6 = in6_addr()
        return ip.withCString { inet_pton(AF_INET, $0, &v4) == 1 || inet_pton(AF_INET6, $0, &v6) == 1 }
    }

    public static func safeName(_ disposition: String?) -> String {
        let value = disposition ?? ""
        let encoded = captures("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8''([^;]+)", in: value)?.first?.removingPercentEncoding
        let ordinary = captures("(?i)(?:^|;)\\s*filename\\s*=\\s*\"([^\"]*)\"", in: value)?.first
            ?? captures("(?i)(?:^|;)\\s*filename\\s*=\\s*([^;]+)", in: value)?.first
        return sanitizeName(encoded ?? ordinary ?? "gdrive-download.bin")
    }

    public static func sanitizeName(_ raw: String) -> String {
        let cleaned = raw.unicodeScalars.map { scalar -> String in
            if scalar == "/" || scalar == "\\" || CharacterSet.controlCharacters.contains(scalar)
                || scalar.properties.generalCategory == .format { return "_" }
            return String(scalar)
        }.joined().trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        var result = ""
        for character in cleaned {
            guard result.utf8.count + String(character).utf8.count <= 180 else { break }
            result.append(character)
        }
        return result.isEmpty ? "gdrive-download.bin" : result
    }

    static func token(_ text: String, min: Int = 1) -> Bool {
        (min...200).contains(text.utf8.count) && text.utf8.allSatisfy { (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }
    }

    static func uniqueQuery(_ items: [URLQueryItem]) throws -> [String: String] {
        var result: [String: String] = [:]
        for item in items {
            guard result[item.name] == nil, let value = item.value else { throw DriveError.message("链接参数重复或无效。") }
            result[item.name] = value
        }
        return result
    }
}

func captures(_ pattern: String, in text: String) -> [String]? {
    guard let regex = try? NSRegularExpression(pattern: pattern),
          let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
    return (1..<match.numberOfRanges).compactMap { Range(match.range(at: $0), in: text).map { String(text[$0]) } }
}

/// Deliberately small, non-executing parser for Google's public GET download form.
/// It cannot submit login forms, script actions, cookies, or arbitrary hidden fields.
public enum DriveConfirmation {
    public static let limit = 524_288
    public static func target(html: String, base: URL, original: URL) throws -> URL {
        guard html.utf8.count <= limit, DriveLinks.allowed(base),
              let originalComponents = URLComponents(url: original, resolvingAgainstBaseURL: false) else {
            throw DriveError.message("确认页过大或地址无效。")
        }
        let originalQuery = try DriveLinks.uniqueQuery(originalComponents.queryItems ?? [])
        let regex = try NSRegularExpression(pattern: "(?is)<form\\b([^>]*)>(.*?)</form\\s*>")
        for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            let source = html as NSString
            let attributes = try attrs(source.substring(with: match.range(at: 1)))
            guard attributes["method", default: "get"].lowercased() == "get",
                  let action = attributes["action"], let url = URL(string: action, relativeTo: base)?.absoluteURL,
                  DriveLinks.allowed(url),
                  ["drive.google.com", "drive.usercontent.google.com"].contains(url.host?.lowercased() ?? ""),
                  ["/uc", "/download"].contains(url.path),
                  var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { continue }
            var fields = try DriveLinks.uniqueQuery(components.queryItems ?? [])
            let body = source.substring(with: match.range(at: 2))
            let inputs = try NSRegularExpression(pattern: "(?is)<input\\b([^>]*)>")
            for input in inputs.matches(in: body, range: NSRange(body.startIndex..., in: body)) {
                let values = try attrs((body as NSString).substring(with: input.range(at: 1)))
                guard let name = values["name"] else { continue }
                guard ["id", "export", "confirm", "uuid", "resourcekey"].contains(name),
                      let value = values["value"], value.utf8.count <= 256, fields[name] == nil,
                      ["hidden", "submit"].contains(values["type", default: "hidden"].lowercased()) else {
                    throw DriveError.message("确认表单包含不支持或重复的字段。")
                }
                fields[name] = value
            }
            guard fields["id"] == originalQuery["id"], let confirm = fields["confirm"], DriveLinks.token(confirm),
                  fields["export"] == nil || fields["export"] == "download",
                  fields.keys.allSatisfy({ ["id", "export", "confirm", "uuid", "resourcekey"].contains($0) }),
                  fields["uuid"].map({ DriveLinks.token($0) }) ?? true,
                  fields["resourcekey"] == nil || fields["resourcekey"] == originalQuery["resourcekey"] else {
                throw DriveError.message("确认页文件标识或资源密钥不匹配。")
            }
            fields["resourcekey"] = originalQuery["resourcekey"]
            components.queryItems = fields.keys.sorted().map { URLQueryItem(name: $0, value: fields[$0]) }
            guard let target = components.url, DriveLinks.allowed(target) else { throw DriveError.message("确认地址无效。") }
            return target
        }
        throw DriveError.message("Google 返回登录、权限、配额或不支持的确认页。请确认“知道链接的任何人”可下载；本版本不登录。")
    }

    private static func attrs(_ text: String) throws -> [String: String] {
        let regex = try NSRegularExpression(pattern: "(?is)([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        var result: [String: String] = [:]
        let source = text as NSString
        for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            let name = source.substring(with: match.range(at: 1)).lowercased()
            guard result[name] == nil else { throw DriveError.message("确认表单属性重复。") }
            let value = (2...4).first(where: { match.range(at: $0).location != NSNotFound }).map { source.substring(with: match.range(at: $0)) } ?? ""
            result[name] = value.replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">").replacingOccurrences(of: "&amp;", with: "&")
        }
        return result
    }
}
