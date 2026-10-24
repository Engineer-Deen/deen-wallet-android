# Run from the deen-wallet-app folder (the one with capacitor.config.json)
$ErrorActionPreference = 'Stop'
$new = 'DeenWallet Transfer'
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Patch($path, [scriptblock]$fn) {
  if (-not (Test-Path $path)) { Write-Host "missing   $path"; return }
  $path = (Resolve-Path $path).Path
  $raw = [IO.File]::ReadAllText($path)
  $out = & $fn $raw
  if ($out -ceq $raw) { Write-Host "no change $path"; return }
  Copy-Item $path "$path.bak" -Force
  [IO.File]::WriteAllText($path, $out, $utf8)
  Write-Host "changed   $path"
}

Patch 'capacitor.config.json' { param($t) $t -replace '"appName"\s*:\s*"[^"]*"', ('"appName": "' + $new + '"') }

Patch 'android/app/src/main/res/values/strings.xml' { param($t)
  $t = $t -replace '(<string name="app_name">)[^<]*(</string>)', ('${1}' + $new + '${2}')
  $t = $t -replace '(<string name="title_activity_main">)[^<]*(</string>)', ('${1}' + $new + '${2}')
  $t }

Patch 'android/app/src/main/java/com/deenwallet/app/DeenWalletUpdateManager.java' { param($t)
  $t = $t.Replace('"DeenWallet update available"', '"DeenWallet Transfer update available"')
  $t = $t.Replace('A newer version of DeenWallet is available', 'A newer version of DeenWallet Transfer is available')
  $t = $t.Replace('"Updating DeenWallet"', '"Updating DeenWallet Transfer"')
  $t = $t.Replace('"Allow DeenWallet updates"', '"Allow DeenWallet Transfer updates"')
  $t = $t.Replace('"DeenWallet could not download the update. "', '"DeenWallet Transfer could not download the update. "')
  $t = $t.Replace('directly from DeenWallet. Enable', 'directly from DeenWallet Transfer. Enable')
  $t = $t.Replace('then return to DeenWallet and tap Update again.', 'then return to DeenWallet Transfer and tap Update again.')
  $t }
Write-Host "Done. Now run: npx cap sync android   then build."
