# Crowd dense baseline (фаза 0)

> **Исторический документ** эпохи TukTuk (сейчас продукт называется **Qq**). Описанный здесь экспериментальный режим не входит в продуктовый UI Qq и отключён в Core-сборке (`QQ_CORE_ONLY`). Сохранено как история измерений BLE.

Шаблон полевого замера плотности BLE. Цель — доказать, что режим **Толпа (CROWD)** улучшает выживание эфира при 20 / 50 / 100 телефонах рядом.

## Метрики (Observatory + logcat)

| Метрика | Где смотреть |
|---------|----------------|
| Peak advertisers / dense window | Hub → Толпа / Delivery Observatory (`denseWindowPeers`, `scanPeersPeak`) |
| GATT connect start/ok/fail | `MeshDutyTelemetry` snapshot |
| Crowd frames tx/rx/forward | `crowdFramesTx`, `crowdFramesRx`, `crowdFramesForwarded` |
| Battery drain % за сессию | Observatory sparkline |
| Duty preset | ECONOMY / NORMAL / MAX / **CROWD** |

## Протокол

1. N телефонов с одним APK, BLE on, GPS/nearby permission granted.
2. Один «якорь» пишет короткий PUBLIC через вкладку **Толпа**; остальные смотрят ленту.
3. Запись 10 мин в **NORMAL**, затем 10 мин в **CROWD** (или auto-crowd).
4. Заполнить таблицу ниже; приложить скрин Observatory.

### Таблица результатов

| N | Preset | peak peers | GATT ok/fail | crowd rx | drain % / 10m | заметки |
|---|--------|------------|--------------|----------|---------------|---------|
| 20 | NORMAL | | | | | |
| 20 | CROWD | | | | | |
| 50 | NORMAL | | | | | |
| 50 | CROWD | | | | | |
| 100 | NORMAL | | | | | |
| 100 | CROWD | | | | | |

## Критерий «лучше выживание»

При том же N: CROWD даёт **≥2×** crowd-кадров доставленных на ≥50% узлов **или** заметно меньше GATT-fail при сравнимом drain.
