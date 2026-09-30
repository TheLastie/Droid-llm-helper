Фиксированный debug-keystore (правило 6 playbook).

debug.keystore.b64 - base64 от бинарного keystore, лежит в репозитории открыто.
Debug-подпись - не секрет, а стабильность важнее: без фиксированного ключа
каждая CI-сборка не встаёт поверх предыдущей (INSTALL_FAILED_UPDATE_INCOMPATIBLE),
а пользователь в поле не может обновить приложение.

Пароли: offlineref / offlineref (alias: offlineref).
При необходимости пересоздать локально:
  keytool -genkeypair -v -keystore debug.keystore -alias offlineref     -keyalg RSA -keysize 2048 -validity 10000     -storepass offlineref -keypass offlineref     -dname "CN=OfflineRef, OU=Dev, O=OfflineRef, C=RU"
  base64 -w0 debug.keystore > debug.keystore.b64
ВНИМАНИЕ: пересоздание = все уже установленные APK перестанут обновляться.
