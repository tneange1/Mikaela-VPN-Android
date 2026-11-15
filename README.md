<div align="center">

# Mikaela VPN для Android

Приложение на Kotlin и Jetpack Compose, ядро Xray-core

[**Скачать APK**](https://github.com/tneange1/Mikaela-VPN-Android/releases/latest/download/Mikaela-VPN.apk)

</div>

## Статус

Каркас: вход по почте и паролю, подписка из Remnawave, список серверов с реальным пингом, подключение через VpnService, устройства, темы, принудительное обновление.
В планах: продление и пополнение внутри приложения, докупка устройств, маршрутизация приложений, другие способы входа.

## Как устроено

- Конфиг Xray берётся из подписки Remnawave без изменений; заменяются только входящие подключения: вместо них используется TUN от `VpnService`.
- Дескриптор TUN передаётся ядру через переменную `XRAY_TUN_FD` (поддержка Android в Xray-core).
- Ядро собирается в GitHub Actions через gomobile из `core/` в `app/libs/xraycore.aar`.
- Само приложение исключено из VPN (`addDisallowedApplication`): Xray работает в его процессе, и его трафик не попадает обратно в туннель. Поэтому пинг показывает настоящую задержку до сервера.
- Вход и запросы идут на `https://cabinet.mikaelavpn.xyz`, подписка запрашивается с User-Agent `MikaelaVPN/…`.

## Сборка и выпуск

1. Один раз добавьте секреты репозитория (Settings → Secrets and variables → Actions):
   `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`. Без ключа APK подписывается временным ключом, и обновить поверх установленного приложение не получится.
2. Каждый push в `main` собирает APK в Artifacts.
3. Чтобы выпустить версию: Releases → *Draft a new release* → тег `v1.0.1` → *Publish*. К релизу прикрепится `Mikaela-VPN.apk`.
4. Постоянная ссылка на последнюю версию: `https://github.com/tneange1/Mikaela-VPN-Android/releases/latest/download/Mikaela-VPN.apk`

Установленное приложение проверяет релизы раз в 15 минут. Когда выходит новая версия, интерфейс размывается и появляется окно с кнопкой «Обновить» (скачивание APK; установку подтверждает сам Android).

## Локально

Нужны Android Studio, JDK 17 и Go. Соберите ядро (`core/`), положите `xraycore.aar` в `app/libs/`, а `geoip.dat` и `geosite.dat` в `app/src/main/assets/`, затем откройте проект в Android Studio.
