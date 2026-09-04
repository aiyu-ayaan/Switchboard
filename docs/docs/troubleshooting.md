# Troubleshooting & Frequently Asked Questions

This guide provides solutions for common connectivity, hardware, and permission issues when setting up and running Switchboard.

---

## 📶 Connection & Pairing Issues

### "Cannot connect to desktop" / Connection Timeout
1. **Verify Same Wi-Fi Network / Subnet**:
   - Ensure your phone and computer are on the same local network subnet.
   - If your router broadcasts both 2.4 GHz and 5.0 GHz bands, verify that **AP Isolation** or **Client Isolation** is disabled in your router settings.
2. **Check Windows Firewall**:
   - Windows Defender Firewall may block incoming connections on port `9427`.
   - Allow port `9427` through Windows Firewall:
     ```powershell
     New-NetFirewallRule -DisplayName "Switchboard Daemon" -Direction Inbound -LocalPort 9427 -Protocol TCP -Action Allow
     ```
3. **Disable Active VPNs**:
   - Third-party desktop VPNs (or mobile VPNs) often route all traffic through a remote tunnel, preventing direct local LAN communication. Turn off local network kill-switches or bypass LAN traffic in VPN settings.

### Pairing QR Code Expired
- Pairing codes expire automatically after 5 minutes for security.
- On your desktop app, click **New code** or refresh the Paired Devices tab to generate a fresh QR code.

---

## 🖥️ Display & DDC/CI Issues

### "Monitor brightness does not change"
1. **Check Monitor OSD Settings**:
   - Many external monitors ship with DDC/CI disabled by default.
   - Use the physical buttons on your monitor to open the On-Screen Display (OSD) menu. Look for **DDC/CI**, **Command Interface**, or **Communication** under System or Miscellaneous settings, and ensure it is set to **Enable**.
2. **Display Cable Quirks**:
   - Some cheap HDMI or DisplayPort adapters / KVM switches omit the I2C DDC communication pins (pins 15 and 16 on HDMI, or AUX channel on DP). Connecting directly to the graphics card or using a certified cable resolves this.
3. **Multi-Monitor Display Mode**:
   - DDC/CI requires each monitor to have an independent hardware head. If Windows display settings are set to **Duplicate / Clone Displays**, DDC/CI controls may only apply to the primary panel. Set display mode to **Extend desktop to this display**.

### "Laptop display shows brightness but no contrast"
- Internal laptop displays (LVDS / eDP) do not have hardware contrast adjustment mechanisms. Contrast control is intentionally hidden for internal screens.

---

## 🔊 Audio & Media Issues

### "Per-app mixer is empty or missing an application"
- Windows WASAPI only creates an audio session once an application actively produces sound. If Chrome or a game is launched but has not played any audio yet, Windows does not register a session. Start playback for 1-2 seconds, and the app slider will appear.

### "Album artwork is not showing on Android"
- Album art extraction requires the active music player to publish artwork through the Windows System Media Transport Controls (SMTC) API. Spotify, Apple Music, and modern browsers (Chrome/Edge) support this out-of-the-box. Ensure your browser media session flags have not been disabled.

---

## 📱 Android Client Tips

### File Transfers Pause in Background
- If Android terminates transfers when the screen is locked:
  1. Go to Android **Settings ➔ Apps ➔ Switchboard**.
  2. Select **Battery ➔ Unrestricted**.
  3. Ensure **Notifications** are permitted so the foreground service notification can maintain continuous network execution.
