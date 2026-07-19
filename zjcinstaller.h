#ifndef ZJCINSTALLER_H
#define ZJCINSTALLER_H

// ============================================================================
// ZjcInstaller —— zjc_worker 分离后按需安装/上报（第三十二章）
//
// 背景：zjc_worker（流量整形子进程）已从 Phoenix 主工程分离，不再随主程序打包、
//   不放主进程目录。改由服务器下发：Phoenix 启动时检查本机是否已装 zjc_worker
//   服务、版本是否最新，缺失/过旧就从服务器下载到 %ProgramData%\zjc_worker\ 并
//   注册服务，最后把「是否安装成功 + 版本」上报后端（总后台可见各 PC 安装情况）。
//
// 全流程在后台线程用 WinHTTP 完成（不阻塞 UI、无需 Qt 事件循环），仅 Windows 生效。
//
// 服务器约定：
//   GET  {baseUrl}/api/zjc/latest   → { "version":"1.0.0",
//                                       "files":[ {"name":"zjc_worker.exe","url":"..."},
//                                                 {"name":"WinDivert.dll","url":"..."},
//                                                 {"name":"WinDivert64.sys","url":"..."} ] }
//   POST {baseUrl}/api/zjc/report   ← { "pcDeviceId","version","installed":bool,"error" }
//
// 本地版本文件：%ProgramData%\zjc_worker\zjc_worker.version（由 zjc_worker --install 写）
// ============================================================================

#include <QString>

namespace ZjcInstaller {

// 启动后台安装检查（幂等；重复调用会被忽略）。
//   baseUrl     : 后端根地址（HttpClient::baseUrl()）
//   pcDeviceId  : 本机 PC 设备号（上报用）
void ensureInstalledAsync(const QString &baseUrl, const QString &pcDeviceId);

// ⭐ 2026-07-11：检测本机是否安装了主流 AI 编程工具（Cursor / VS Code / Codex /
//   Claude / Windsurf / Copilot 等）。命中任一即返回 true。
//   方式：已安装程序(注册表卸载项 DisplayName) + 运行进程名 + 常见安装目录。
//   toolsCsvOut 非空时写回命中的工具名（逗号分隔，用于上报/展示）。
//   结果进程内缓存一次（多次调用不重复扫描）。非 Windows 恒 false。
bool detectAiCodingTools(QString *toolsCsvOut = nullptr);

} // namespace ZjcInstaller

#endif // ZJCINSTALLER_H
