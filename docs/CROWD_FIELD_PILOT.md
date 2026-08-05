# Crowd field pilot + proof (фаза 4)

## Публичный densе-отчёт

После заполнения [CROWD_DENSE_BENCH.md](CROWD_DENSE_BENCH.md) опубликовать краткий отчёт (issue / release notes):

- N, локация (без PII), дата, APK versionName
- NORMAL vs CROWD: peak peers, GATT fail ratio, crowd delivery, battery
- Один вывод: улучшает ли CROWD **выживание эфира** на этом N

## Security review (crowd surface)

Проверить вручную / checklist:

- [ ] Crowd frame не подменяет подписанный PRIVATE/IDENTITY JSON path
- [ ] В CROWD не пишем лишние MAC в PeerDirectory
- [ ] Event Anchor слушает только LAN, без auth — только для офлайн-события (не экспозить в интернет)
- [ ] Комната без OTP: passphrase опциональна; не обещать e2e для PUBLIC crowd
- [ ] Auto-crowd имеет cooldown и auto-exit (4h)

## Полевой пилот

1. 10–30 человек, одно событие, QR якоря + QR комнаты.
2. Зафиксировать: сколько Android / сколько iPhone через PWA, сколько SOS/PUBLIC увидело ≥половину сети.
3. Feedback: батарея, шум ленты, понятность вкладки **Толпа**.
