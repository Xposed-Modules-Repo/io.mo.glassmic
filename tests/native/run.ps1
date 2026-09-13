$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual Studio C++ build tools are required.' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
$out = Join-Path $repo 'build\native-regression'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source = Join-Path $PSScriptRoot 'pcm_regression.cpp'
$stubs = Join-Path $PSScriptRoot 'stubs'
$exe = Join-Path $out 'pcm_regression.exe'
$obj = Join-Path $out 'pcm_regression.obj'
# cmd is used solely to load MSVC's compiler environment and run cl, not for filesystem operations.
$compile = "call `"$vcvars`" >nul && cl /nologo /std:c++17 /EHsc /utf-8 /D_CRT_SECURE_NO_WARNINGS /I`"$stubs`" `"$source`" /Fe:`"$exe`" /Fo:`"$obj`""
& $env:ComSpec /d /s /c $compile
if ($LASTEXITCODE -ne 0) { throw 'Native regression compilation failed.' }
& $exe
if ($LASTEXITCODE -ne 0) { throw 'Native regression tests failed.' }
