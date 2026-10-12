$ErrorActionPreference = 'Stop'

$mcJar        = "C:\Users\Strange\Downloads\Compressed\Yuri-windows\Yuri.jar"
$meowtilsJar  = "C:\Users\Strange\Pictures\Meowtils-Lunar\Meowtils-Lunar\Meowtils.jar"
$lwjglJar     = "C:\Users\Strange\AppData\Roaming\.minecraft\libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar"

$javac = "C:\Program Files\Java\jdk-22\bin\javac.exe"
$jar   = "C:\Program Files\Java\jdk-22\bin\jar.exe"

$outDir = ".\out"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path "$outDir\META-INF" | Out-Null

Write-Host "Compiling HitFlick and Main..."
& $javac -source 8 -target 8 -proc:none -cp "$mcJar;$meowtilsJar;$lwjglJar" -d $outDir Main.java HitFlick.java
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }

# Write required Meowtils metadata
[IO.File]::WriteAllText("$outDir\META-INF\meowtils.extension", "main=Main`n")

# Package jar
if (Test-Path "HitFlick.meowtils") { Remove-Item -Force "HitFlick.meowtils" }
& $jar cf HitFlick.meowtils -C $outDir .

# Copy to extensions folder
$destExt = "C:\Users\Strange\AppData\Roaming\.minecraft\meowtils\extensions\HitFlick.meowtils"
Copy-Item -Force "HitFlick.meowtils" $destExt

Write-Host "Successfully built HitFlick.meowtils and copied to $destExt"
