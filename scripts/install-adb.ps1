# 同じネットワーク上の複数の Android 端末へ、adb で APK をまとめてインストールする（Windows PowerShell 版）
# 使い方: .\scripts\install-adb.ps1 <APK> <端末一覧ファイル>
#         .\scripts\install-adb.ps1 <APK> <IP[:ポート]> [<IP[:ポート]> ...]
#  端末一覧ファイルは 1 行に 1 台（"192.168.1.101" や "192.168.1.102:5555  # 入口"）。
#  # 以降はコメント、空行は無視。ポートを省略すると 5555。例は scripts\devices.example.txt
#  adb が PATH に無いときは -Adb "C:\platform-tools\adb.exe" で指定する。上書きインストール(-r)なので設定や許可は残る
#  スクリプトの実行が止められる場合: powershell -ExecutionPolicy Bypass -File .\scripts\install-adb.ps1 ...
param(
    [Parameter(Position = 0, Mandatory = $true)][string]$Apk,
    [Parameter(Position = 1, Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Target,
    [string]$Adb = 'adb'
)

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { Write-Error "APK が見つかりません: $Apk"; exit 2 }
if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    Write-Error 'adb が見つかりません（platform-tools を入れて PATH を通すか、-Adb でパスを指定してください）'; exit 2
}
$Apk = (Resolve-Path -LiteralPath $Apk).Path

# 端末一覧の読み込み（ファイルならその中身、そうでなければ引数をそのまま IP として扱う）
$targets = @()
if ($Target.Count -eq 1 -and (Test-Path -LiteralPath $Target[0] -PathType Leaf)) {
    foreach ($line in Get-Content -LiteralPath $Target[0] -Encoding UTF8) {
        $t = (($line -split '#')[0]) -replace '\s', ''
        if ($t) { $targets += $t }
    }
} else {
    $targets = $Target
}
if ($targets.Count -eq 0) { Write-Error '端末が 1 台も指定されていません'; exit 2 }

# adb の出力（stderr 含む）を文字列で受け取る
function Invoke-Adb { & $Adb @args 2>&1 | ForEach-Object { "$_" } }

$ok = @(); $ng = @()
foreach ($t in $targets) {
    if ($t -notmatch ':') { $t = "${t}:5555" }
    Write-Host "=== $t ==="

    # 接続できない端末で待たされないよう 15 秒で打ち切る
    $job = Start-Job -ScriptBlock { param($a, $x) & $a connect $x 2>&1 | ForEach-Object { "$_" } } -ArgumentList $Adb, $t
    $done = Wait-Job $job -Timeout 15
    $conn = if ($done) { (Receive-Job $job) -join "`n" } else { '' }
    Remove-Job $job -Force
    if ($conn -notmatch '(?i)connected to|already connected') {
        Write-Host '  接続できません（IP・同じネットワークか・ADB デバッグがオンかを確認）'
        $ng += "$t (接続失敗)"; continue
    }

    # 初回は端末側で「USB デバッグを許可」を押すまで unauthorized になる
    $state = ((Invoke-Adb -s $t get-state) -join '').Trim()
    if ($state -ne 'device') {
        Write-Host "  状態: $state（端末の画面で接続の許可を押してから、もう一度実行してください）"
        $ng += "$t ($state)"; continue
    }

    $out = Invoke-Adb -s $t install -r $Apk
    if (($out -join "`n") -match '(?m)^Success') {
        Write-Host '  インストール成功'; $ok += $t
    } else {
        $out | ForEach-Object { Write-Host "  $_" }
        $ng += "$t (インストール失敗)"
    }
    Invoke-Adb disconnect $t | Out-Null
}

Write-Host ''
Write-Host "成功: $($ok.Count) 台 / 失敗: $($ng.Count) 台"
foreach ($t in $ng) { Write-Host "  失敗: $t" }
if ($ng.Count -gt 0) { exit 1 }
