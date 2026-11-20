# Manual APK build for Windows (no Gradle): kotlinc -> aapt2 -> d8 -> zipalign -> apksigner
# Always signs with the existing debug.keystore so `adb install -r` updates cleanly in-place.
# Uses relative paths after Push-Location so Windows aapt2.exe handles non-ASCII parent paths.
$ErrorActionPreference = "Stop"

$Proj = $PSScriptRoot
Push-Location $Proj
try {
    $Out = "manual-build"
    $Ks  = "debug.keystore"
    if (!(Test-Path $Ks)) {
        throw "debug.keystore not found at $Proj\$Ks — refusing to regenerate key (would break update install)."
    }

    $Java = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
    if (!(Test-Path $Java)) { throw "Java not found at $Java" }
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

    $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    $Bt  = (Get-ChildItem (Join-Path $Sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
    $AndroidJar = (Get-ChildItem (Join-Path $Sdk "platforms") -Recurse -Filter "android.jar" | Sort-Object FullName -Descending | Select-Object -First 1).FullName

    $Cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
    $KcJar   = (Get-ChildItem "$Cache\org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.10" -Recurse -Filter "kotlin-compiler-embeddable-*.jar" | Select-Object -First 1).FullName
    $StdJar  = (Get-ChildItem "$Cache\org.jetbrains.kotlin\kotlin-stdlib\2.2.10" -Recurse -Filter "kotlin-stdlib-*.jar" | Select-Object -First 1).FullName
    $CoroJar = (Get-ChildItem "$Cache\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2" -Recurse -Filter "kotlinx-coroutines-core-jvm-*.jar" | Select-Object -First 1).FullName
    $AnnJar  = (Get-ChildItem "$Cache\org.jetbrains\annotations\23.0.0" -Recurse -Filter "annotations-*.jar" | Select-Object -First 1).FullName

    Write-Host "Build-Tools : $Bt"
    Write-Host "Android Jar : $AndroidJar"
    Write-Host "Kotlinc Jar : $KcJar"
    Write-Host "Stdlib Jar  : $StdJar"

    if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
    New-Item -ItemType Directory -Path "$Out\dex" -Force | Out-Null

    $KtFiles = (Get-ChildItem "app\src\main\java" -Recurse -Filter "*.kt").FullName
    Write-Host "== 1. Kotlin compile ($($KtFiles.Count) files) =="
    & $Java -Xmx768m -cp "$KcJar;$StdJar;$CoroJar;$AnnJar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -classpath "$AndroidJar;$StdJar" `
        -jvm-target 17 `
        -no-stdlib `
        -no-reflect `
        -d "$Out\app-classes.jar" `
        @KtFiles
    if ($LASTEXITCODE -ne 0) { throw "kotlinc failed ($LASTEXITCODE)" }

    Write-Host "== 2. aapt2 compile res =="
    & (Join-Path $Bt "aapt2.exe") compile --dir "app\src\main\res" -o "$Out\res.zip"
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

    Write-Host "== 3. aapt2 link =="
    $LinkArgs = @(
        "link", "-o", "$Out\app-unsigned.apk",
        "-I", $AndroidJar,
        "--manifest", "app\src\main\AndroidManifest.xml"
    )
    if (Test-Path "app\src\main\assets") { $LinkArgs += @("-A", "app\src\main\assets") }
    $LinkArgs += "$Out\res.zip"
    & (Join-Path $Bt "aapt2.exe") @LinkArgs
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

    Write-Host "== 4. d8 =="
    & (Join-Path $Bt "d8.bat") --lib $AndroidJar --min-api 26 --output "$Out\dex" `
        "$Out\app-classes.jar" $StdJar
    if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

    Write-Host "== 5. add dex files =="
    python -c "
import sys, zipfile, glob, os
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for dex in sorted(glob.glob(os.path.join(dexdir, '*.dex'))):
        z.write(dex, os.path.basename(dex))
        print('added', os.path.basename(dex))
" "$Out\app-unsigned.apk" "$Out\dex"
    if ($LASTEXITCODE -ne 0) { throw "dex zip failed ($LASTEXITCODE)" }

    Write-Host "== 6. zipalign =="
    & (Join-Path $Bt "zipalign.exe") -f -p 4 "$Out\app-unsigned.apk" "$Out\app-aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

    Write-Host "== 7. sign & verify =="
    $FinalApk = "$Out\FakeCall.apk"
    & (Join-Path $Bt "apksigner.bat") sign --ks $Ks --ks-pass pass:android --key-pass pass:android `
        --out $FinalApk "$Out\app-aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed ($LASTEXITCODE)" }

    & (Join-Path $Bt "apksigner.bat") verify --print-certs $FinalApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed ($LASTEXITCODE)" }

    Get-Item $FinalApk | Select-Object FullName, Length, LastWriteTime
    Write-Host "BUILD_OK"
} finally {
    Pop-Location
}
