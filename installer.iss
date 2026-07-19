; Phoenix 安装程序脚本 (Inno Setup)
; 需要安装 Inno Setup: https://jrsoftware.org/isinfo.php

#define MyAppName "Phoenix"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Acard"
#define MyAppExeName "Phoenix.exe"

[Setup]
; 应用程序信息
AppId={{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={code:GetDefaultInstallDir}
DefaultGroupName={#MyAppName}
; 输出设置
OutputDir=D:\javafx\Acard\aic\Aifs\installer_output
OutputBaseFilename=Phoenix_Setup_{#MyAppVersion}
; 压缩设置
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
; 界面设置
WizardStyle=modern
SetupIconFile=D:\javafx\Acard\aic\Aifs\images\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
; 权限设置
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible
; 其他设置
DisableProgramGroupPage=yes
DisableDirPage=no
DisableWelcomePage=no
ShowLanguageDialog=no

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加选项:"

[Files]
; ⭐ zjc_worker 已分离（第三十二章）：不再随主程序打包，PC 端登录后由 ZjcInstaller
;   从 CDN 自动下载安装为独立 Windows 服务（金凤凰）。故此处不再包含 zjc_worker.exe。
Source: "D:\javafx\Acard\aic\Aifs\release\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
; 先静默安装 VC++ 运行库（如果需要）
Filename: "{app}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "正在安装 Visual C++ 运行库..."; Flags: waituntilterminated skipifdoesntexist
; ⭐ zjc_worker 已分离：不再随安装包启动，改由 Phoenix 登录后 ZjcInstaller 从 CDN 自动装成服务。
; 安装完成后运行程序
Filename: "{app}\{#MyAppExeName}"; Description: "立即运行 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 卸载时删除主程序生成的文件（保留 zjc_worker 相关文件）
Type: filesandordirs; Name: "{app}\captures"
Type: filesandordirs; Name: "{app}\logs"
Type: files; Name: "{app}\*.log"

[Code]
// 自动选择安装盘：优先选非 C 盘根目录，无其他盘则用 C:\ 根目录
function GetDefaultInstallDir(Param: String): String;
var
  I: Integer;
  DriveRoot: String;
begin
  // 从 D(68) 到 Z(90) 找第一个存在的非 C 盘
  for I := 68 to 90 do
  begin
    DriveRoot := Chr(I) + ':\';
    if DirExists(DriveRoot) then
    begin
      Result := DriveRoot + '{#MyAppName}';
      Exit;
    end;
  end;
  // 没有其他盘，装到 C:\ 根目录
  Result := 'C:\{#MyAppName}';
end;

// 安装完成后清理 VC++ 安装程序
procedure CurStepChanged(CurStep: TSetupStep);
var
  VCRedistPath: String;
begin
  if CurStep = ssPostInstall then
  begin
    // 删除 VC++ 运行库安装程序（节省空间）
    VCRedistPath := ExpandConstant('{app}\vc_redist.x64.exe');
    if FileExists(VCRedistPath) then
      DeleteFile(VCRedistPath);
  end;
end;

// 卸载前只关闭主进程。zjc_worker 已分离为独立 Windows 服务（装在 ProgramData，
// 不在 {app} 安装目录内），卸载本安装包不会触碰它，天然保留继续运行。
function InitializeUninstall(): Boolean;
var
  ResultCode: Integer;
begin
  Result := True;
  Exec('taskkill.exe', '/F /IM Phoenix.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;
