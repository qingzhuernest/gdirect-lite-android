import SwiftUI
import GDirectCore
import QuickLook
import UIKit

@main
struct GDirectLiteApp: App {
    @StateObject private var store = DownloadModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(store)
                .onChange(of: phase) { if $0 == .background { store.enteredBackground() } }
        }
    }
}

struct SavedFile: Identifiable {
    let url: URL
    let size: Int64
    let date: Date
    var id: String { url.path }
    var name: String { url.lastPathComponent }
    var symbol: String {
        switch url.pathExtension.lowercased() {
        case "pdf": return "doc.richtext"
        case "png", "jpg", "jpeg", "gif", "heic": return "photo"
        case "mp4", "mov", "m4v": return "video"
        case "mp3", "m4a", "wav": return "waveform"
        case "zip", "7z", "gz": return "doc.zipper"
        default: return "doc"
        }
    }
}

@MainActor
final class DownloadModel: ObservableObject {
    @Published var link = ""
    @Published var ip = ""
    @Published var connections = UserDefaults.standard.integer(forKey: "connections") == 0 ? 4 : UserDefaults.standard.integer(forKey: "connections") {
        didSet { UserDefaults.standard.set(connections, forKey: "connections") }
    }
    @Published var busy = false
    @Published var testing = false
    @Published var message = "等待公开文件链接"
    @Published var bytes: Int64 = 0
    @Published var total: Int64?
    @Published var activeConnections = 1
    @Published var files: [SavedFile] = []
    @Published var error: String?
    @Published var completedFile: URL?
    private let downloader = DriveDownloader()
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var backgroundCancelled = false
    let destination: URL
    let cache: URL
    init() {
        let fm = FileManager.default
        destination = fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads", isDirectory: true)
        cache = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("GDirectChunks", isDirectory: true)
        reload()
    }
    func reload() {
        do {
            try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
            var excluded = destination
            var flags = URLResourceValues(); flags.isExcludedFromBackup = true
            try excluded.setResourceValues(flags)
            files = try FileManager.default.contentsOfDirectory(at: destination, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey], options: [.skipsHiddenFiles]).compactMap { url in
                let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
                guard values.isRegularFile == true else { return nil }
                return SavedFile(url: url, size: Int64(values.fileSize ?? 0), date: values.contentModificationDate ?? .distantPast)
            }.sorted { $0.date > $1.date }
        } catch { self.error = "无法读取本地文件：\(error.localizedDescription)" }
    }
    func start() {
        guard !busy && !testing else { return }
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try DriveLinks.normalize(text)
            guard address.isEmpty || DriveLinks.validIP(address) else { throw DriveError.message("请输入有效的 IPv4 或 IPv6 地址，或留空使用系统网络。") }
        } catch { self.error = error.localizedDescription; return }
        generation = UUID(); let token = generation
        busy = true; backgroundCancelled = false; bytes = 0; total = nil; completedFile = nil; error = nil
        message = "正在连接…"; UIApplication.shared.isIdleTimerDisabled = true
        let count = connections
        task = Task { [weak self] in
            guard let self else { return }
            defer { self.busy = false; self.task = nil; UIApplication.shared.isIdleTimerDisabled = false; self.reload() }
            do {
                let file = try await self.downloader.download(link: text, ip: address.isEmpty ? nil : address, connections: count, destination: self.destination, cache: self.cache) { [weak self] update in
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.generation == token, self.busy else { return }
                        self.bytes = update.completed; self.total = update.total; self.message = update.message; self.activeConnections = update.connections
                    }
                }
                self.completedFile = file
                let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? self.bytes
                self.bytes = size; self.total = size
                self.message = "下载完成，可打开或保存到“文件”"
            } catch is CancellationError {
                self.message = self.backgroundCancelled ? "进入后台已暂停；回到前台后重新下载可复用完整分块。" : "已取消；重新下载相同链接可复用已验证的完整分块。"
            } catch {
                self.message = "下载未完成"; self.error = error.localizedDescription
            }
        }
    }
    func cancel() { generation = UUID(); task?.cancel(); message = "正在停止…" }
    func enteredBackground() { if busy || testing { backgroundCancelled = true; cancel() } }
    func test() {
        guard !busy && !testing else { return }
        let address = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard address.isEmpty || DriveLinks.validIP(address) else { error = "请输入有效 IP 地址。"; return }
        testing = true; error = nil; message = "正在检测 HTTPS…"
        task = Task { [weak self] in
            guard let self else { return }; defer { self.testing = false; self.task = nil }
            do { self.message = try await self.downloader.testConnection(ip: address.isEmpty ? nil : address) }
            catch is CancellationError { self.message = "检测已取消" }
            catch { self.error = error.localizedDescription; self.message = "连接检测失败" }
        }
    }
    func clearCache() {
        guard !busy && !testing else { return }
        do { if FileManager.default.fileExists(atPath: cache.path) { try FileManager.default.removeItem(at: cache) }; message = "续传缓存已清除" }
        catch { self.error = error.localizedDescription }
    }
    func delete(_ file: SavedFile) {
        do { try FileManager.default.removeItem(at: file.url); reload() }
        catch { self.error = error.localizedDescription }
    }
}

struct ContentView: View {
    @EnvironmentObject private var store: DownloadModel
    @State private var showDownload = false
    var body: some View {
        TabView {
            DownloadHome(showDownload: $showDownload)
                .tabItem { Label("下载", systemImage: "arrow.down.circle") }
            LocalFilesView().tabItem { Label("文件", systemImage: "folder") }
            SettingsView().tabItem { Label("设置", systemImage: "gearshape") }
        }
        .tint(Color.accentColor)
        .sheet(isPresented: $showDownload) { DownloadForm() }
        .alert("操作未完成", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
            Button("好", role: .cancel) { store.error = nil }
        } message: { Text(store.error ?? "") }
    }
}

struct DownloadHome: View {
    @EnvironmentObject private var store: DownloadModel
    @Binding var showDownload: Bool
    @State private var share: SavedFile?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("公开链接，无需登录", systemImage: "link").font(.headline)
                    Text("下载公开分享的 Google Drive 单个文件，完成后打开预览或保存到“文件”。").foregroundStyle(.secondary)
                    Button { showDownload = true } label: { Label("添加下载", systemImage: "plus.circle.fill").font(.headline).padding(.vertical, 6) }
                        .disabled(store.busy || store.testing)
                }
                Section("传输") {
                    if store.busy {
                        if let total = store.total, total > 0 { ProgressView(value: Double(store.bytes), total: Double(total)) }
                        else { ProgressView() }
                    }
                    Text(store.message).accessibilityLabel("传输状态：\(store.message)")
                    if store.bytes > 0 || store.total != nil {
                        Text("\(formatBytes(store.bytes)) / \(store.total.map(formatBytes) ?? "未知大小")").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        if store.busy { Text("实际连接数：\(store.activeConnections)").font(.caption).foregroundStyle(.secondary) }
                    }
                    if store.busy || store.testing { Button("取消", role: .destructive) { store.cancel() }.frame(minHeight: 44) }
                    if let url = store.completedFile {
                        ShareLink(item: url) { Label("分享或保存到“文件”", systemImage: "square.and.arrow.up") }.frame(minHeight: 44)
                    }
                }
                Section("使用提示") {
                    Label("下载期间请保持应用在前台", systemImage: "iphone")
                    Text("进入后台会暂停。服务器支持 Range 与强 ETag 时，重试可复用已验证的 4 MiB 分块。").font(.footnote).foregroundStyle(.secondary)
                    Text("暂不支持私人文件、Google 文档导出、文件夹、上传或账户管理。").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("GDirect Lite")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { showDownload = true } label: { Image(systemName: "plus") }.accessibilityLabel("添加公开链接下载").disabled(store.busy || store.testing) } }
        }
    }
}

struct DownloadForm: View {
    @EnvironmentObject private var store: DownloadModel
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://drive.google.com/file/d/…/view", text: $store.link, axis: .vertical)
                        .lineLimit(3...6).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        .accessibilityLabel("Google Drive 公开文件链接")
                    PasteButton(payloadType: String.self) { values in if let first = values.first { store.link = first } }.frame(minHeight: 44)
                } header: { Text("文件链接") } footer: { Text("支持公开单文件链接及 resourcekey。共享设置需允许知道链接的任何人下载。") }
                Section("下载偏好") {
                    Picker("并行连接", selection: $store.connections) { Text("1 条").tag(1); Text("4 条").tag(4); Text("8 条").tag(8) }
                    Text("服务器支持强 ETag 和 HTTP Range 时使用分块下载，否则自动单连接下载。").font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    TextField("IP 地址（可留空）", text: $store.ip).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.asciiCapable)
                        .accessibilityLabel("指定 Google IP 地址，可选")
                } header: { Text("高级：指定 IP") } footer: { Text("留空使用系统网络。指定 IP 仅替换连接地址；仍验证真实 Google 主机名和证书。") }
                Section { Button("开始下载") { store.start(); if store.busy { dismiss() } }.font(.headline).frame(maxWidth: .infinity, minHeight: 44).disabled(store.link.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.busy || store.testing) }
            }
            .navigationTitle("公开链接下载").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
    }
}

struct LocalFilesView: View {
    @EnvironmentObject private var store: DownloadModel
    @State private var query = ""
    @State private var preview: SavedFile?
    @State private var sharing: SavedFile?
    @State private var deleting: SavedFile?
    var filtered: [SavedFile] { query.isEmpty ? store.files : store.files.filter { $0.name.localizedCaseInsensitiveContains(query) } }
    var body: some View {
        NavigationStack {
            List {
                if filtered.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: query.isEmpty ? "folder" : "magnifyingglass").font(.largeTitle).foregroundStyle(.blue)
                        Text(query.isEmpty ? "还没有下载文件" : "没有匹配的文件").font(.headline)
                        Text(query.isEmpty ? "完成下载后，文件会显示在这里。" : "尝试其他名称。").foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity).padding(.vertical, 40).listRowBackground(Color.clear)
                }
                ForEach(filtered) { file in
                    HStack(spacing: 14) {
                        Image(systemName: file.symbol).font(.title2).foregroundStyle(.blue).frame(width: 32)
                        Button { preview = file } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(file.name).lineLimit(2).foregroundStyle(.primary)
                                Text("\(formatBytes(file.size)) · \(file.date.formatted(date: .abbreviated, time: .omitted))").font(.caption).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).frame(minHeight: 48)
                        Menu {
                            Button("打开预览", systemImage: "eye") { preview = file }
                            Button("分享或保存", systemImage: "square.and.arrow.up") { sharing = file }
                            Button("删除本地文件", systemImage: "trash", role: .destructive) { deleting = file }
                        } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44).contentShape(Rectangle()) }
                            .accessibilityLabel("\(file.name)的操作")
                    }
                }
            }
            .listStyle(.plain).navigationTitle("文件").searchable(text: $query, prompt: "搜索已下载文件")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { store.reload() } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("刷新本地文件") } }
            .onAppear { store.reload() }
            .sheet(item: $preview) { file in
                NavigationStack {
                    FilePreview(url: file.url).navigationTitle(file.name).navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { preview = nil } } }
                }
            }
            .sheet(item: $sharing) { file in ShareSheet(url: file.url) }
            .confirmationDialog("删除本地文件？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button("删除", role: .destructive) { if let file = deleting { store.delete(file) }; deleting = nil }
            } message: { Text("仅删除这台设备上的下载副本。") }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var store: DownloadModel
    @State private var clear = false
    var body: some View {
        NavigationStack {
            Form {
                Section("连接") {
                    TextField("指定 IP（留空使用系统网络）", text: $store.ip).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.asciiCapable).disabled(store.busy || store.testing)
                    Button(store.testing ? "正在检测…" : "检测 HTTPS 连接") { store.test() }.disabled(store.busy || store.testing).frame(minHeight: 44)
                    if store.testing { Button("取消检测", role: .cancel) { store.cancel() } }
                    Text(store.message).font(.footnote).foregroundStyle(.secondary)
                    Text("使用 Network.framework 连接。指定 IP 时保留真实主机名 SNI，严格校验证书链和主机名。不会安装证书或停用验证。").font(.footnote).foregroundStyle(.secondary)
                }
                Section("传输") {
                    Picker("并行连接", selection: $store.connections) { Text("1 条").tag(1); Text("4 条").tag(4); Text("8 条").tag(8) }.disabled(store.busy || store.testing)
                    Text("单文件上限 50 GiB。分块下载及合并时需额外可用空间；缓存可能被系统清理。").font(.footnote).foregroundStyle(.secondary)
                    Button("清除续传缓存", role: .destructive) { clear = true }.disabled(store.busy || store.testing)
                }
                Section("关于") {
                    LabeledContent("版本", value: "iOS 0.1.0")
                    Text("独立开发，非 Google 官方应用。仅支持公开单文件下载；没有账户登录、私人云盘浏览、上传、中转或后台持续下载。").font(.footnote).foregroundStyle(.secondary)
                    Text("下载内容留在设备本地。使用系统分享菜单可保存到“文件”。文件及续传缓存不会加入 iCloud 备份。").font(.footnote).foregroundStyle(.secondary)
                    Text("自有应用代码 Apache-2.0；使用 Apple 系统框架，无第三方网络或界面库。").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
            .confirmationDialog("清除续传缓存？", isPresented: $clear, titleVisibility: .visible) { Button("清除缓存", role: .destructive) { store.clearCache() } } message: { Text("已下载文件会保留，未完成任务需要重新下载。") }
        }
    }
}

private func formatBytes(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .binary) }
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct FilePreview: UIViewControllerRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    func makeUIViewController(context: Context) -> QLPreviewController { let c = QLPreviewController(); c.dataSource = context.coordinator; return c }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}
