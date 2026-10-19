<#
  Выпускает новую версию Platter: телефон, компьютер или оба сразу.

  Запуск из папки проекта:
      powershell -ExecutionPolicy Bypass -File release.ps1            # телефон и компьютер
      powershell -ExecutionPolicy Bypass -File release.ps1 -PhoneOnly # только телефон
      powershell -ExecutionPolicy Bypass -File release.ps1 -PcOnly    # только компьютер
      powershell -ExecutionPolicy Bypass -File release.ps1 -DryRun    # только показать номер, ничего не менять

  Номер берётся сам: последний релиз на GitHub + 1 (1.0.4 -> 1.0.5). Свой можно задать: -Version 1.1.0.
  Описание изменений - из notes.md; перепишите его до запуска.

  Что делает, по порядку (BUILDING.md, 3b):
    1. вписывает номер в app/build.gradle и/или desktop/build.gradle.kts
    2. собирает APK и/или MSI, MSI копирует под именем app-platter-release.msi
    3. показывает описание и список файлов для коммита и ждёт "y"
    4. git add -A, git commit, git push
    5. gh release create с файлами и notes.md (только компьютер - с --latest=false)
  До шага 3 ничего не отправляется: ответ не "y" оставляет всё у вас, номер можно вернуть через git checkout.
#>
param(
    [string]$Version,
    [switch]$PhoneOnly,
    [switch]$PcOnly,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$Repo = 'Timoha589/Platter'
$AppGradle = 'app/build.gradle'
$DesktopGradle = 'desktop/build.gradle.kts'
$Apk = 'app/build/outputs/apk/platter/release/app-platter-release.apk'
$MsiDir = 'desktop/build/compose/binaries/main/msi'
$Msi = "$MsiDir/app-platter-release.msi"

if ($PhoneOnly -and $PcOnly) { throw 'Нужно что-то одно: -PhoneOnly или -PcOnly (без них выпускаются оба).' }
$phone = -not $PcOnly
$pc = -not $PhoneOnly

function Step($text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

# Запускает внешнюю программу и останавливает скрипт, если она завершилась с ошибкой.
function Run($what, [scriptblock]$command) {
    & $command
    if ($LASTEXITCODE -ne 0) { throw "$what - ошибка (код $LASTEXITCODE). Ничего не опубликовано дальше этого шага." }
}

# Файлы переписываются в UTF-8 без BOM, как они и лежат в репозитории.
function Set-VersionIn($file, $pattern, $replacement) {
    $path = Join-Path $PSScriptRoot $file
    $text = [IO.File]::ReadAllText($path)
    if ($text -notmatch $pattern) { throw "В $file не найдена строка с номером версии." }
    $new = [regex]::Replace($text, $pattern, $replacement, 1)
    [IO.File]::WriteAllText($path, $new, (New-Object Text.UTF8Encoding($false)))
}

# --- номер ----------------------------------------------------------------------

Step 'Номер версии'
$tags = gh release list --repo $Repo --limit 100 --json tagName --jq '.[].tagName'
if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать релизы с GitHub (gh auth login?).' }
$released = @($tags | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | ForEach-Object { [version]($_.Substring(1)) })
$latest = ($released | Sort-Object -Descending | Select-Object -First 1)

if (-not $Version) {
    if (-not $latest) { throw 'На GitHub нет ни одного релиза vX.Y.Z; задайте номер сами: -Version 1.0.0' }
    $Version = '{0}.{1}.{2}' -f $latest.Major, $latest.Minor, ($latest.Build + 1)
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Номер должен быть вида 1.0.5, а не '$Version'." }
$v = [version]$Version
if ($latest -and $v -le $latest) { throw "Номер только растёт: на GitHub уже есть $latest, а задан $Version." }
if ($released -contains $v) { throw "Релиз v$Version уже существует." }
if ($v.Major -gt 255 -or $v.Minor -gt 255 -or $v.Build -gt 255) { throw 'Каждая часть номера должна быть меньше 256 (ограничение MSI).' }

$what = if ($phone -and $pc) { 'телефон и компьютер' } elseif ($phone) { 'только телефон' } else { 'только компьютер' }
Write-Host "Последний релиз: $latest. Выпускается: $Version ($what)."
if ($DryRun) { Write-Host 'Пробный запуск: больше ничего не делаю.'; exit 0 }

# --- номер в файлах -------------------------------------------------------------

Step "Вписываю $Version"
if ($phone) { Set-VersionIn $AppGradle "def platterVersionName = '[^']*'" "def platterVersionName = '$Version'" }
if ($pc) { Set-VersionIn $DesktopGradle 'val platterVersion = "[^"]*"' "val platterVersion = `"$Version`"" }

# --- сборка ---------------------------------------------------------------------

if ($phone) {
    Step 'Собираю APK'
    Run 'Сборка APK' { & "$PSScriptRoot\gradlew.bat" assemblePlatterRelease }
    if (-not (Test-Path $Apk)) { throw "Нет $Apk. Если появился app-platter-release-unsigned.apk - Gradle не нашёл ключ подписи (BUILDING.md, 2.2)." }
}
if ($pc) {
    Step 'Собираю MSI'
    Run 'Сборка MSI' { & "$PSScriptRoot\desktop\gradlew.bat" -p desktop packageMsi }
    $built = "$MsiDir/Platter-$Version.msi"
    if (-not (Test-Path $built)) { throw "Нет ${built}: номер в $DesktopGradle не тот, или сборка не удалась." }
    Copy-Item $built $Msi -Force
}

# --- проверка перед отправкой ---------------------------------------------------

Step 'Описание изменений (notes.md)'
Get-Content notes.md -Encoding UTF8 | ForEach-Object { Write-Host "    $_" }

Step 'Войдёт в коммит'
git add -A
git status --short | ForEach-Object { Write-Host "    $_" }

Write-Host ''
$answer = Read-Host "Выпустить Platter $Version с этим описанием? Будет git push и релиз на GitHub (y/n)"
if ($answer -ne 'y') {
    git reset -q
    Write-Host 'Остановлено. Ничего не отправлено; сборки и номер в файлах остались у вас.'
    exit 1
}

# --- отправка -------------------------------------------------------------------

Step 'Коммит и git push'
Run 'Коммит' { git commit -q -m "Platter $Version" }
Run 'git push' { git push }

Step "Релиз v$Version"
$files = @()
if ($phone) { $files += $Apk }
if ($pc) { $files += $Msi }
$extra = @()
# Релиз без APK не должен становиться "последним": телефоны ищут APK именно там.
if (-not $phone) { $extra += '--latest=false' }
Run 'gh release create' { gh release create "v$Version" @files --repo $Repo --title "Platter $Version" --notes-file notes.md @extra }

Write-Host ''
Write-Host "Готово: https://github.com/$Repo/releases/tag/v$Version" -ForegroundColor Green
