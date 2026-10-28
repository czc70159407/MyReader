param(
    [Parameter(Mandatory=$true)][string]$Action,
    [string]$File,
    [string]$Out = "D:\develop\code\MyReader\tools\acceptance\m9"
)
$adb = "D:\develop\Android\Sdk\platform-tools\adb.exe"
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Dump([string]$name) {
    # 红线：设备侧验收文件统一落 Download/MyReaderTest/xml（AGENTS.md 工作规则 10）
    & $adb shell uiautomator dump /sdcard/Download/MyReaderTest/xml/m9_$name.xml | Out-Null
    & $adb pull /sdcard/Download/MyReaderTest/xml/m9_$name.xml "$Out\$name.xml" | Out-Null
    return [System.IO.File]::ReadAllText("$OUT\$name.xml", [System.Text.Encoding]::UTF8)
}

function Center([string]$bounds) {
    # bounds like [x0,y0][x1,y1]
    $m = [regex]::Matches($bounds, '\[(\d+),(\d+)\]')
    $x0 = [int]$m[0].Groups[1].Value; $y0 = [int]$m[0].Groups[2].Value
    $x1 = [int]$m[1].Groups[1].Value; $y1 = [int]$m[1].Groups[2].Value
    return @{ X = ($x0 + $x1) / 2; Y = ($y0 + $y1) / 2 }
}

function FindNode([string]$xml, [string]$text) {
    foreach ($n in [regex]::Matches($xml, '<node\b[^>]*>')) {
        $s = $n.Value
        if ($s -match 'text="([^"]*)"') {
            $t = $Matches[1]
            if ($t -eq $text -and $s -match 'bounds="(\[[0-9,]+\]\[[0-9,]+\])"') {
                return Center $Matches[1]
            }
        }
    }
    return $null
}

function FindNodePrefix([string]$xml, [string]$prefix) {
    foreach ($n in [regex]::Matches($xml, '<node\b[^>]*>')) {
        $s = $n.Value
        if ($s -match 'text="([^"]*)"') {
            $t = $Matches[1]
            if ($t.StartsWith($prefix) -and $s -match 'bounds="(\[[0-9,]+\]\[[0-9,]+\])"') {
                return Center $Matches[1]
            }
        }
    }
    return $null
}

switch ($Action) {
    "import" {
        # shelf import button
        & $adb shell input tap 564 216; Start-Sleep -Seconds 2
        & $adb shell input tap 618 216; Start-Sleep -Seconds 2   # 文件 tab
        & $adb shell input tap 552 1601; Start-Sleep -Seconds 2  # 下载 shortcut
        $xml = Dump "picker"
        $c = FindNode $xml $File
        if (-not $c) { Write-Host "FILE-NOT-FOUND: $File"; exit 3 }
        & $adb shell input tap $c.X $c.Y; Start-Sleep -Seconds 2
        $xml = Dump "picker"
        $add = FindNodePrefix $xml "添加"
        if ($add) { & $adb shell input tap $add.X $add.Y } else { Write-Host "ADD-BTN-NOT-FOUND"; exit 4 }
        Start-Sleep -Seconds 2
        $xml = Dump "app"
        $ok = FindNode $xml "确定"
        if ($ok) { & $adb shell input tap $ok.X $ok.Y } else { Write-Host "CONFIRM-NOT-FOUND"; exit 5 }
        Start-Sleep -Seconds 3
        Write-Host "IMPORT-DONE: $File"
    }
    "shelf" {
        $xml = Dump "app"
        Write-Host "---- shelf texts ----"
        [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne "" } | Select-Object -Unique
    }
    "open" {
        # tap a document card by title text
        $xml = Dump "app"
        $c = FindNode $xml $File
        if (-not $c) { Write-Host "CARD-NOT-FOUND: $File"; exit 6 }
        & $adb shell input tap $c.X $c.Y; Start-Sleep -Seconds 3
        Write-Host "OPENED: $File"
    }
    default { Write-Host "unknown action" }
}
