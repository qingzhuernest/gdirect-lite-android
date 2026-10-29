import XCTest
import Network
import Foundation
@testable import GDirectCore

final class TransportTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws { root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true) }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }
    func testDiskCreationFailure() async {
        do {
            _ = try await HTTPTransfer().request(URL(string: "https://drive.google.com/")!, ip: nil, directory: root.appendingPathComponent("does-not-exist"), limit: 10)
            XCTFail("invalid output accepted")
        } catch {}
    }
    func testCancellationBeforeStartCleansOutput() async throws {
        let transfer = HTTPTransfer(); transfer.cancel()
        do {
            _ = try await transfer.request(URL(string: "https://drive.google.com/")!, ip: nil, directory: root, limit: 10)
            XCTFail("cancelled transfer accepted")
        } catch is CancellationError {} catch { XCTFail("Unexpected: \(error)") }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    }
    func testIdleTimeoutOnSilentLoopbackPeer() async throws {
        let listener = try SilentListener()
        let port = try await listener.start()
        defer { listener.stop() }
        do {
            _ = try await HTTPTransfer().request(URL(string: "https://drive.google.com/")!, ip: "127.0.0.1", directory: root, limit: 100, idleTimeout: 0.15, totalTimeout: 2, port: port)
            XCTFail("silent peer succeeded")
        } catch { XCTAssertTrue(error.localizedDescription.contains("超时"), error.localizedDescription) }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    }
    func testCancellationDuringHandshake() async throws {
        let listener = try SilentListener(); let port = try await listener.start(); defer { listener.stop() }
        let transfer = HTTPTransfer()
        let directory = root!
        let task = Task { try await transfer.request(URL(string: "https://drive.google.com/")!, ip: "127.0.0.1", directory: directory, limit: 100, idleTimeout: 5, port: port) }
        try await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        do { _ = try await task.value; XCTFail("cancelled handshake succeeded") } catch is CancellationError {} catch { XCTFail("Unexpected: \(error)") }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    }
}

private final class SilentListener: @unchecked Sendable {
    let queue = DispatchQueue(label: "GDirect.tests.loopback")
    let listener: NWListener
    var peers: [NWConnection] = []
    var resumed = false
    init() throws { listener = try NWListener(using: .tcp, on: .any) }
    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { state in
                guard !self.resumed else { return }
                switch state {
                case .ready: self.resumed = true; continuation.resume(returning: self.listener.port!.rawValue)
                case .failed(let e): self.resumed = true; continuation.resume(throwing: e)
                default: break
                }
            }
            listener.newConnectionHandler = { connection in self.peers.append(connection); connection.start(queue: self.queue) }
            listener.start(queue: queue)
        }
    }
    func stop() { queue.sync { listener.cancel(); peers.forEach { $0.cancel() }; peers.removeAll() } }
}
