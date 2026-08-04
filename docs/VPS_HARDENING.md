# VPS SSH hardening (Этап 1)

Host: `node.tuktuk.dev` / `157.228.136.239` · Ubuntu 24.04 · user `root`

## Требование

Доступ **только по SSH-ключу** (Ed25519). Парольный вход для root отключён.

## Проверено (smoke)

```text
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes root@157.228.136.239 'echo OK'
```

Ожидаемо:

- вход по ключу — успех;
- `PasswordAuthentication` / keyboard-interactive — отказ.

## Эффективные директивы sshd

Файлы:

- `/etc/ssh/sshd_config` — `PermitRootLogin prohibit-password`, `KbdInteractiveAuthentication no`
- `/etc/ssh/sshd_config.d/50-cloud-init.conf` — `PasswordAuthentication no` (не оставлять `yes`)
- `/etc/ssh/sshd_config.d/99-tuktuk-hardening.conf`:

```
PasswordAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
```

После правок: `sshd -t && systemctl restart ssh`.

## Ключи оператора

Публичный ключ разработчика лежит в `/root/.ssh/authorized_keys` (`chmod 600`, dir `700`).
Приватный ключ **только** на машине разработчика (`~/.ssh/id_ed25519`), не в git.
