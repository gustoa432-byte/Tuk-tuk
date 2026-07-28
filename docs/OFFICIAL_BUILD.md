# Официальная сборка TukTuk

## Что значит «официальная»

В Профиле строка **«Сборка: официальная подпись»** означает: установленный APK подписан тем же release-сертификатом, чей SHA-256 зашит в сборку (`BuildConfig.EXPECTED_RELEASE_CERT_SHA256`). Это **не** защита от извлечения APK и **не** запрет форков в mesh.

Отдельно ключ автора (`SecurityConfig.AUTHOR_PUBLIC_KEY`) нужен только для подписи mesh-пакетов `SYSTEM_ANNOUNCEMENT` / `VERSION_ANNOUNCEMENT`. Приватный ключ автора в APK не входит.

| Сборка | Профиль |
|--------|---------|
| debug | «Сборка: debug (не Play)» |
| release, cert совпал | «Сборка: официальная подпись» |
| release, cert другой | «Сборка: неофициальная подпись» |
| release, SHA-256 не задан | «Сборка: подпись неизвестна / локальная» |

## Первый раз (на машине разработчика)

```bash
./scripts/setup-official-signing.sh
```

Скрипт идемпотентен: не перезапишет существующий keystore без `FORCE=1`.

Создаёт (всё gitignored):

- `app/release.keystore`
- `keystore.properties`
- `secrets/RELEASE_KEY_BACKUP.txt` — пароли + SHA-256, **сохрани офлайн**
- `secrets/author_private.pem` — **сохрани офлайн**
- `secrets/author_pub.b64` — публичный ключ (уже вшит в `SecurityConfig` после setup)

## Бэкап

Скопируй офлайн:

1. `app/release.keystore`
2. `secrets/RELEASE_KEY_BACKUP.txt` (или `keystore.properties`)
3. `secrets/author_private.pem`

Потерял бэкап → новые APK нельзя обновлять поверх старых с той же подписью (пользователям придётся удалять приложение).

## Сборка

```bash
./gradlew :app:assembleRelease
```

APK: `app/build/outputs/apk/release/Tuktuk.apk`

Без `keystore.properties` задача `assembleRelease` завершится ошибкой с подсказкой запустить setup-скрипт.

## Подпись объявления автора

```bash
echo -n "текст объявления" | openssl dgst -sha256 -sign secrets/author_private.pem | base64 -w0
```

Подробнее: [SECURITY.md](SECURITY.md).
