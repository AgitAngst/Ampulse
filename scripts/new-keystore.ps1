# Создаёт ключ подписи выпуска Ampulse и пишет keystore.properties в корень проекта.
#
# Ключ один на всё время жизни приложения: потерянный ключ — новая версия больше не встанет поверх
# установленной ни у кого (anvil/docs/ANDROID.md, раздел 4). Поэтому:
#   * файл ключа лежит вне репозитория (по умолчанию в ampulse-scratch\keys), копия — во втором месте;
#   * существующий файл ключа и существующий keystore.properties скрипт не перезаписывает;
#   * пароли спрашиваются скрытым вводом и нигде не печатаются.
#
# Запуск (PowerShell 5.1):  powershell -ExecutionPolicy Bypass -File scripts\new-keystore.ps1
#   -Path   куда положить ключ (по умолчанию E:\dev_personal\ampulse-scratch\keys\ampulse-release.jks)
#   -Alias  псевдоним ключа (по умолчанию ampulse)
#   -DName  имя владельца в сертификате (оно открыто в каждом APK; по умолчанию CN=Ampulse)

[CmdletBinding()]
param(
    [string]$Path = 'E:\dev_personal\ampulse-scratch\keys\ampulse-release.jks',
    [string]$Alias = 'ampulse',
    [string]$DName = 'CN=Ampulse'
)

$ErrorActionPreference = 'Stop'

# JDK — только читается: JAVA_HOME, а без него D:\Android\jdk-21 (anvil/docs/ANDROID.md, раздел 1).
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\Android\jdk-21' }
$keytool = Join-Path $javaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) {
    throw "keytool not found: $keytool (set JAVA_HOME to a JDK, e.g. D:\Android\jdk-21)"
}

# Корень проекта — папка выше scripts\.
$projectRoot = Split-Path -Parent $PSScriptRoot
$propsPath = Join-Path $projectRoot 'keystore.properties'
$Path = [IO.Path]::GetFullPath($Path)

# Сначала все проверки, потом ключ: чтобы не остаться с ключом, для которого некуда записать пароли.
if (Test-Path -LiteralPath $Path) {
    throw "Key file already exists, not overwriting: $Path"
}
if (Test-Path -LiteralPath $propsPath) {
    throw "keystore.properties already exists, not overwriting: $propsPath (move it away and run again)"
}

# Скрытый ввод в строку. Строка живёт только в памяти скрипта; в журнал и на экран она не попадает.
function Read-SecretText([string]$Prompt) {
    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# Пароль спрашиваем дважды. Один пароль и на хранилище, и на ключ: хранилище PKCS12 (по умолчанию
# в JDK 9+) не поддерживает разные пароли, keytool молча взял бы пароль хранилища.
# Только печатные ASCII: keystore.properties читается как Latin-1, и пароль должен пройти в секрет GitHub без искажений.
$password = Read-SecretText 'New key password (min 8 chars, ASCII only)'
$again = Read-SecretText 'Repeat the password'
if ($password -cne $again) { throw 'The passwords do not match. Nothing was created.' }
if ($password.Length -lt 8) { throw 'The password is too short (min 8 chars). Nothing was created.' }
if ($password -cnotmatch '^[\x21-\x7E]([\x20-\x7E]*[\x21-\x7E])?$') {
    throw 'The password must be printable ASCII without spaces at the ends. Nothing was created.'
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null

# Пароль передаём keytool через переменную окружения процесса (-storepass:env), а не в командной строке,
# чтобы он не был виден в списке процессов.
$env:AMPULSE_KEYTOOL_PASSWORD = $password
try {
    Write-Host "Generating RSA 4096 key (this takes a few seconds) ..."
    & $keytool -genkeypair `
        -keystore $Path -storetype PKCS12 `
        -alias $Alias -dname $DName `
        -keyalg RSA -keysize 4096 -validity 36500 `
        -storepass:env AMPULSE_KEYTOOL_PASSWORD -keypass:env AMPULSE_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $Path)) { throw "keytool did not create the key file: $Path" }

    # Формат .properties: обратная косая — служебный знак, её удваиваем; путь пишем с прямыми косыми.
    function ConvertTo-PropValue([string]$Value) { return $Value.Replace('\', '\\') }
    $lines = @(
        '# Release signing key of Ampulse. Local file, listed in .gitignore - never commit it.'
        ('store=' + (ConvertTo-PropValue ($Path.Replace('\', '/'))))
        ('storePassword=' + (ConvertTo-PropValue $password))
        ('alias=' + (ConvertTo-PropValue $Alias))
        ('keyPassword=' + (ConvertTo-PropValue $password))
    )
    [IO.File]::WriteAllText($propsPath, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))
}
finally {
    Remove-Item Env:\AMPULSE_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    $again = $null
}

Write-Host ''
Write-Host 'Done.'
Write-Host "  Key file:   $Path"
Write-Host "  Properties: $propsPath (local, in .gitignore)"
Write-Host ''
Write-Host 'What to do next:'
Write-Host '  1. Save a copy of the key file in a second safe place. Without this key nobody can update'
Write-Host '     an installed Ampulse with a new version - ever.'
Write-Host '  2. Add four secrets on GitHub (repository Settings > Secrets and variables > Actions):'
Write-Host '       AMPULSE_KEYSTORE           the key file in base64, one line. Copy it to the clipboard with:'
Write-Host "         [Convert]::ToBase64String([IO.File]::ReadAllBytes('$Path')) | Set-Clipboard"
Write-Host '       AMPULSE_KEYSTORE_PASSWORD  the password you just typed'
Write-Host "       AMPULSE_KEY_ALIAS          $Alias"
Write-Host '       AMPULSE_KEY_PASSWORD       the same password (PKCS12 keeps one password for store and key)'
Write-Host '  3. Check locally:  ./gradlew :app:assembleGithubRelease --max-workers=2'
