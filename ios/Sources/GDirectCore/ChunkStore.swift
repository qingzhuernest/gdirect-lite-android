import Foundation
import CryptoKit

/// Stores only complete, verified chunks. The manifest contains no URL or account data.
actor ChunkStore {
    private struct Manifest: Codable {
        var version = 1
        let etag: String
        let size: Int64
        let chunkSize: Int64
        var hashes: [String: String] = [:]
    }
    private let folder: URL
    private var manifest: Manifest
    private var manifestURL: URL { folder.appendingPathComponent("manifest.json") }
    init(folder: URL, etag: String, size: Int64, chunkSize: Int64) throws {
        guard RangeRules.strongETag(etag) != nil, size > 0, chunkSize > 0 else { throw DriveError.message("续传信息无效。") }
        self.folder = folder
        let url = folder.appendingPathComponent("manifest.json")
        if let bytes = try? Data(contentsOf: url), let old = try? JSONDecoder().decode(Manifest.self, from: bytes), old.version == 1, old.etag == etag, old.size == size, old.chunkSize == chunkSize {
            self.manifest = old
        } else {
            if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
            self.manifest = Manifest(etag: etag, size: size, chunkSize: chunkSize)
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }
    private func chunk(_ index: Int) -> URL { folder.appendingPathComponent("\(index).chunk") }
    private func expected(_ index: Int) throws -> Int64 {
        guard index >= 0, Int64(index) < (manifest.size - 1) / manifest.chunkSize + 1 else { throw DriveError.message("分块索引无效。") }
        return min(manifest.chunkSize, manifest.size - Int64(index) * manifest.chunkSize)
    }
    private func digest(_ url: URL, size: Int64) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256(); var total: Int64 = 0
        while let data = try handle.read(upToCount: 65_536), !data.isEmpty {
            try Task.checkCancellation()
            total += Int64(data.count)
            guard total <= size else { throw DriveError.message("缓存分块大小错误。") }
            hash.update(data: data)
        }
        guard total == size else { throw DriveError.message("缓存分块不完整。") }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    func verifiedChunks() throws -> Set<Int> {
        var valid = Set<Int>()
        for (key, hash) in manifest.hashes {
            try Task.checkCancellation()
            guard let index = Int(key), let size = try? expected(index) else { continue }
            do {
                if try digest(chunk(index), size: size) == hash { valid.insert(index) }
            } catch is CancellationError { throw CancellationError() }
            catch { continue }
        }
        manifest.hashes = manifest.hashes.filter { Int($0.key).map(valid.contains) == true }
        try save()
        return valid
    }
    func commit(index: Int, source: URL) throws {
        let hash = try digest(source, size: expected(index))
        try Task.checkCancellation()
        let target = chunk(index)
        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
        try FileManager.default.moveItem(at: source, to: target)
        manifest.hashes[String(index)] = hash
        try save()
    }
    private func save() throws { try JSONEncoder().encode(manifest).write(to: manifestURL, options: .atomic) }
    func assemble(to output: URL) throws {
        guard FileManager.default.createFile(atPath: output.path, contents: nil) else { throw DriveError.message("无法创建合并文件。") }
        let out = try FileHandle(forWritingTo: output)
        do {
            let count = Int((manifest.size - 1) / manifest.chunkSize + 1)
            for index in 0..<count {
                try Task.checkCancellation()
                let size = try expected(index)
                guard let hash = manifest.hashes[String(index)], try digest(chunk(index), size: size) == hash else { throw DriveError.message("缓存校验失败，请重新下载。") }
                let input = try FileHandle(forReadingFrom: chunk(index))
                do {
                    while let data = try input.read(upToCount: 65_536), !data.isEmpty {
                        try Task.checkCancellation(); try out.write(contentsOf: data)
                    }
                    try input.close()
                } catch { try? input.close(); throw error }
            }
            try out.synchronize(); try out.close()
        } catch { try? out.close(); try? FileManager.default.removeItem(at: output); throw error }
    }
    func remove() throws { try FileManager.default.removeItem(at: folder) }
}
