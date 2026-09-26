#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo "Applying Telegram open-access + tools patches..."
if [[ -f patches/telegram-open-and-tools-handlers.patch ]]; then
  sed 's|TelegramCommandHandlers.kt|app/src/main/java/me/rerere/rikkahub/service/TelegramCommandHandlers.kt|g' \
    patches/telegram-open-and-tools-handlers.patch | patch -p0 || true
fi
if [[ -f patches/telegram-open-and-tools-service.patch ]]; then
  sed 's|TelegramBotService.kt|app/src/main/java/me/rerere/rikkahub/service/TelegramBotService.kt|g' \
    patches/telegram-open-and-tools-service.patch | patch -p0 || true
fi
echo "Done. Rebuild the APK in Android Studio."
