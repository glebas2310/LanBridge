# LanBridge Windows Firewall Setup Script
# Run as Administrator in PowerShell to configure inbound ports for LanBridge

$ErrorActionPreference = "Stop"

Write-Host "=== LanBridge Firewall Configuration ===" -ForegroundColor Cyan

# 1. Inbound rule for WSS / File Streaming (TCP 8443)
$tcpRuleName = "LanBridge WSS & File Streaming (TCP 8443)"
$existingTcp = Get-NetFirewallRule -DisplayName $tcpRuleName -ErrorAction SilentlyContinue

if ($existingTcp) {
    Write-Host "[*] Updating existing rule: $tcpRuleName" -ForegroundColor Yellow
    Set-NetFirewallRule -DisplayName $tcpRuleName -Action Allow -Direction Inbound -Protocol TCP -LocalPort 8443 -Profile Any
} else {
    Write-Host "[+] Creating new firewall rule: $tcpRuleName" -ForegroundColor Green
    New-NetFirewallRule -DisplayName $tcpRuleName `
                        -Description "Allows LanBridge encrypted WSS and file transfer streaming between PC and phone" `
                        -Direction Inbound `
                        -Action Allow `
                        -Protocol TCP `
                        -LocalPort 8443 `
                        -Profile Any
}

# 2. Inbound rule for Discovery Beacon (UDP 54321)
$udpRuleName = "LanBridge UDP Discovery (UDP 54321)"
$existingUdp = Get-NetFirewallRule -DisplayName $udpRuleName -ErrorAction SilentlyContinue

if ($existingUdp) {
    Write-Host "[*] Updating existing rule: $udpRuleName" -ForegroundColor Yellow
    Set-NetFirewallRule -DisplayName $udpRuleName -Action Allow -Direction Inbound -Protocol UDP -LocalPort 54321 -Profile Any
} else {
    Write-Host "[+] Creating new firewall rule: $udpRuleName" -ForegroundColor Green
    New-NetFirewallRule -DisplayName $udpRuleName `
                        -Description "Allows LanBridge UDP broadcast discovery packets on local network" `
                        -Direction Inbound `
                        -Action Allow `
                        -Protocol UDP `
                        -LocalPort 54321 `
                        -Profile Any
}

Write-Host "`n[✓] LanBridge firewall rules have been successfully configured!" -ForegroundColor Green
Write-Host "Ports opened: TCP 8443 (WSS/Transfer), UDP 54321 (Discovery)" -ForegroundColor Cyan
