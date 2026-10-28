#!/bin/bash
# Builds MyDrop and wraps it into MyDrop.app (ad-hoc signed, for this Mac only).
set -e
cd "$(dirname "$0")/MyDrop"
swift build -c release
APP="../MyDrop.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp .build/release/MyDrop "$APP/Contents/MacOS/MyDrop"
mkdir -p "$APP/Contents/Resources"
cp ../icon/compiled/AppIcon.icns ../icon/compiled/Assets.car "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>MyDrop</string>
  <key>CFBundleDisplayName</key><string>MyDrop</string>
  <key>CFBundleIdentifier</key><string>com.local.mydrop</string>
  <key>CFBundleExecutable</key><string>MyDrop</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIconName</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>MyDrop needs local network access to find your phone and send files over Wi-Fi.</string>
  <key>LSUIElement</key><true/>
  <key>CFBundleDocumentTypes</key><array><dict>
    <key>CFBundleTypeName</key><string>Any file</string>
    <key>CFBundleTypeRole</key><string>Viewer</string>
    <key>LSItemContentTypes</key><array><string>public.item</string></array>
  </dict></array>
  <key>NSServices</key><array><dict>
    <key>NSMenuItem</key><dict><key>default</key><string>Send to Phone (MyDrop)</string></dict>
    <key>NSMessage</key><string>sendToPhone</string>
    <key>NSPortName</key><string>MyDrop</string>
    <key>NSSendFileTypes</key><array><string>public.item</string></array>
  </dict></array>
  <key>CFBundleURLTypes</key><array><dict>
    <key>CFBundleURLName</key><string>MyDrop</string>
    <key>CFBundleURLSchemes</key><array><string>mydrop</string></array>
  </dict></array>
  <key>NSBonjourServices</key><array><string>_FC9F5ED42C8A._tcp</string><string>_mydropfiles._tcp</string></array>
</dict></plist>
PLIST
# --- Share-menu extension ---
EXT="$APP/Contents/PlugIns/MyDropShare.appex"
mkdir -p "$EXT/Contents/MacOS" "$EXT/Contents/Resources"
cp ../icon/compiled/AppIcon.icns ../icon/compiled/Assets.car "$EXT/Contents/Resources/"
cp .build/release/MyDropShare "$EXT/Contents/MacOS/MyDropShare"
cat > "$EXT/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>MyDrop</string>
  <key>CFBundleDisplayName</key><string>MyDrop</string>
  <key>CFBundleIdentifier</key><string>com.local.mydrop.share</string>
  <key>CFBundleExecutable</key><string>MyDropShare</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIconName</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>XPC!</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSExtension</key><dict>
    <key>NSExtensionPointIdentifier</key><string>com.apple.share-services</string>
    <key>NSExtensionPrincipalClass</key><string>ShareViewController</string>
    <key>NSExtensionAttributes</key><dict>
      <key>NSExtensionActivationRule</key><dict>
        <key>NSExtensionActivationSupportsFileWithMaxCount</key><integer>100</integer>
      </dict>
    </dict>
  </dict>
</dict></plist>
PLIST
cat > ../ext.entitlements <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.app-sandbox</key><true/>
  <key>com.apple.security.files.user-selected.read-only</key><true/>
</dict></plist>
ENT
codesign --force --sign - --entitlements ../ext.entitlements "$EXT"
codesign --force --sign - "$APP"
echo "Built $APP"
