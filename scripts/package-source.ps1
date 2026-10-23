$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot 'deliverables'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$archive = Join-Path $destination 'Shiri-0.3.0-source.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::Open($archive, [System.IO.FileMode]::Create)
$zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $files = @()
    foreach ($folder in @('app/src','app/schemas','core/src','docs','scripts','gradle/wrapper')) {
        $path = Join-Path $projectRoot $folder
        if (Test-Path -LiteralPath $path) { $files += Get-ChildItem -LiteralPath $path -File -Recurse }
    }
    foreach ($name in @('.gitignore','README.md','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','core/build.gradle.kts')) {
        $path = Join-Path $projectRoot $name
        if (Test-Path -LiteralPath $path) { $files += Get-Item -LiteralPath $path }
    }
    foreach ($file in $files | Sort-Object FullName -Unique) {
        $relative = $file.FullName.Substring($projectRoot.TrimEnd('\').Length + 1).Replace('\','/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose(); $stream.Dispose() }
Write-Output $archive
