$projectRoot = "E:\workshop\BankCardReader"

# Remove old git
$gitDir = Join-Path $projectRoot ".git"
if (Test-Path $gitDir) {
    Remove-Item -Recurse -Force $gitDir
    Write-Output "Removed old .git"
}

# Re-init
Push-Location $projectRoot
git init
git add -A
git status --short
Pop-Location
