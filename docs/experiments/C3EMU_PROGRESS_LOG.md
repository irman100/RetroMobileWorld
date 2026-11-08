# Журнал продолжения: Nokia C3-00 / RM-614

**Назначение:** отдельная точка восстановления контекста C3-00. Не смешивать её с журналом Nokia 6300 / RM-217: это разные устройства, firmware loaders и ветки разработки.

**Обновлено:** 8 ноября 2026  
**Рабочая ветка:** feature/c3emu-touch-shell-speed-20261107  
**Ветка-основание:** feature/android-c3emu-integration-20261107  
**Upstream engine:** androettop/c3emu, commit 9b0d3ae2da326e109428f64d8760ea823ee1b648  
**Последний подтверждённый CI для исправления FPSX:** Android CI push run 42457493795 — success, commit 84afcb78bd8c0f70571e68b22de015df9299d406.  
**PR:** https://github.com/irman100/RetroMobileWorld/pull/3 — draft, не слит.

---

## 1. Краткий статус

В приложение добавлена отдельная экспериментальная среда Nokia C3-00 / RM-614 на основе upstream c3emu. Пользователь подтвердил, что установленная GitHub-сборка отображает выбранную оболочку C3-00. При импорте пользовательской прошивки приложение ранее завершалось ошибкой:

FPSX flash address range overflows 32 bits.

Причина найдена в дополнительной проверке Android-предварительного инспектора FPSX. Инспектор требовал, чтобы сумма unsigned 32-bit start address и длины блока помещалась в диапазон 32 бит. Закреплённый upstream c3emu читает адрес как u32 и не делает такой проверки суммы; именованные области MCUSW/PPM размещаются на boot-этапе отдельно. В результате наш предварительный фильтр мог отклонять блок, который upstream parser принимает.

Исправление внесено: убрать только лишнюю проверку address + length. Проверки фактической целостности контейнера сохранены: длина блока ограничена, данные должны полностью помещаться в файл, заголовки и записи должны быть доступны.

**Подтверждено:** исправленный инспектор и тесты прошли Android CI.  
**Пока не подтверждено:** что конкретный пользовательский Phoenix ZIP импортируется целиком и что RM-614 достигает домашнего экрана на физическом телефоне. Успешная установка приложения не является доказательством успешного guest boot.

## 2. Последняя ошибка и причина

Сообщение возникало из app/src/main/java/org/retromobile/world/FirmwareImageInspector.kt до передачи сохранённого файла нативному c3emu backend.

Старое условие:
- разрешало специальный адрес 0xFFFFFFFF;
- иначе требовало flashAddress + dataLength <= 0x1_0000_0000;
- выводило FPSX flash address range overflows 32 bits при нарушении.

Сравнение с upstream crates/c3emu/src/fpsx.rs на commit 9b0d3ae2da326e109428f64d8760ea823ee1b648 показало:
- FPSX named block хранит u32 target address;
- upstream читает адрес как u32;
- upstream проверяет, что данные блока доступны в пределах самого файла;
- upstream не отклоняет блок только из-за того, что адрес плюс длина пересекают границу unsigned 32-bit;
- именованные образы позже извлекаются по региональному имени и размещаются загрузчиком.

Источники:
- https://github.com/androettop/c3emu/blob/9b0d3ae2da326e109428f64d8760ea823ee1b648/crates/c3emu/src/fpsx.rs
- https://github.com/androettop/c3emu/blob/9b0d3ae2da326e109428f64d8760ea823ee1b648/crates/c3emu/src/boot.rs

## 3. Кодовое исправление и тест

В ветке C3-00:
- commit d8d4545fea4c61d9aa27076f187e792b73916c0a: убрать избыточную проверку суммы address + length; адресное u32 поле по-прежнему читается; проверки границ файла сохранены.
- commit 84afcb78bd8c0f70571e68b22de015df9299d406: тест accepts32BitFlashAddressWhoseRegionCrossesUnsignedAddressBoundary заменяет прежнее ожидание отказа. Тест проверяет region PPM по адресу 0xFFFFFFFE с коротким payload, не помещающимся в 32-битную сумму.

CI push run 42457493795:
https://github.com/irman100/RetroMobileWorld/actions/runs/42457493795

Подтверждённые шаги:
- profile/catalog validation — success;
- Python firmware inspection tests — success;
- Android SDK / NDK setup — success;
- Rust Android targets и cargo-ndk — success;
- c3emu JNI build для arm64-v8a, armeabi-v7a и x86_64 — success;
- Android unit tests — success;
- debug APK assembly — success;
- artifact upload — success.

Artifact:
- name: retro-mobile-world-c3emu-debug
- artifact ID: 13608077537
- ZIP SHA-256: 6b8c597fb3cf2484025613b8278fe146b51b4896841db08b54f971f0b8b80e3a
- packaged APK SHA-256: 6947f5e787f6e9412d147357dcd4dff50a5d8e7fe94b113afe9272151416b722
- artifact expiry: 22 November 2026 07:11 UTC.

Этот artifact был собран до увеличения Android versionCode до 5. Он подтверждает исправление инспектора на уровне кода и тестов, но для текущей локальной установки следует собирать APK из обновлённого локального репозитория.

Последний complete checkpoint для version 0.4.1 и Windows build/install scripts:
- commit: 1c63d991738e2b209000271ea86dfb170894f508;
- Android CI push run 42458429582: success; all steps including native build, Android unit tests, APK assembly and artifact upload passed;
- Android CI PR run 42458434131: success;
- artifact ID: 13608921375, name retro-mobile-world-c3emu-debug;
- artifact ZIP SHA-256: 51c17e2b59980ec370ba9957dab1ac8771859cdf705c2e921d08795709de8c81;
- artifact expiry: 22 November 2026 07:24 UTC.

The CI artifact is built with GitHub Actions debug signing. Local Windows Gradle may use a different debug certificate; local physical-device install script deliberately preserves an existing app if Android reports INSTALL_FAILED_UPDATE_INCOMPATIBLE. Do not uninstall unless the user explicitly accepts losing app-private firmware files, saved C: storage and settings.


## 4. Shell и UI

Пользователь выбрал точный front-facing asset docs/75-205-263-02.png; APK получает pinned asset с проверкой Git blob SHA-1. Не заменять его случайной фотографией или внешним изображением.

Пользователь подтвердил, что оболочка применилась в установленной GitHub-сборке. Это подтверждает отображение оболочки, но не подтверждает:
- точное совпадение LCD overlay с прозрачным окном выбранного PNG;
- точную калибровку всех физических key touch zones;
- успешное отображение реальных кадров Series 40.

Эти параметры требуется проверять по скриншоту физического телефона. Не заявлять, что координаты всех кнопок откалиброваны, пока это не подтверждено визуально и интерактивным тестом.

## 5. Локальная сборка и установка на физический телефон

Для рабочей станции пользователь подтвердил:
- ADB находится в D:\Android\Sdk\platform-tools\adb.exe;
- версия ADB: 1.0.41, platform-tools build 37.0.0-14910828;
- Windows 10 build 19045.

Добавлен scripts/build-install-c3emu-phone.ps1. Он:
1. проверяет чистое Git working tree и не стирает незакоммиченные изменения;
2. обновляет только ветку feature/c3emu-touch-shell-speed-20261107 через fast-forward;
3. находит Android SDK, включая D:\Android\Sdk;
4. собирает native JNI libraries;
5. выполняет :app:testDebugUnitTest и :app:assembleDebug;
6. выбирает только авторизованный физический ADB device, исключая emulator-*;
7. требует явный -Serial, когда подключено несколько физических телефонов;
8. устанавливает локальный APK через adb -s SERIAL install -r и запускает org.retromobile.world.

Сам build script scripts/build-c3emu-android.ps1 тоже улучшен: он обнаруживает SDK через ANDROID_SDK_ROOT, ANDROID_HOME, local.properties, D:\Android\Sdk и стандартные каталоги.

Developer options и USB debugging включаются вручную на телефоне. После включения надо разблокировать экран и подтвердить RSA authorization для этого ПК. Некоторые Xiaomi/MIUI могут дополнительно требовать переключатель Install via USB. ADB не должен пытаться тайно активировать настройки разработчика.

Android app version увеличена до 0.4.1-c3emu-rm614, versionCode 5, чтобы локальный APK был явно новее ранее установленной версии 0.4.0 / versionCode 4.

Возможен конфликт подписи: GitHub Actions debug keystore и локальный Windows debug keystore могут различаться. Поэтому сценарий по умолчанию не удаляет ранее установленное приложение при INSTALL_FAILED_UPDATE_INCOMPATIBLE и сохраняет app-private данные. После успешной локальной сборки commit-stamp позволяет повторить только этап установки через -InstallOnly. Только явный флаг -UninstallOnSignatureMismatch разрешает удаление пакета; в сочетании с -InstallOnly он не повторяет сборку. Удаление уничтожает приватную библиотеку прошивок, C: storage и настройки.


Подробная процедура: docs/ANDROID_BUILD_INSTALL.md.

## 6. Следующие проверки на физическом телефоне

Порядок:
1. Выполнить .\scripts\build-install-c3emu-phone.ps1 из D:\RetroMobileWorld-c3emu-20261107.
2. Сохранить вывод ADB с serial и моделью выбранного телефона.
3. Открыть Nokia C3-00 · Open emulator.
4. Импортировать тот же пользовательский Phoenix ZIP для RM-614.
5. Проверить, исчезло ли сообщение FPSX flash address range overflows 32 bits.
6. Если импорт завершён, запустить Series 40 и записать точную диагностику boot.
7. Успех boot подтверждать реальным кадром LCD/домашним экраном и рабочей реакцией клавиш; затем отдельно проверить звук и сохранение C: storage.

Если появится новая ошибка:
- не ослаблять следующие проверки наугад;
- записать точный текст ошибки и этап (import vs native boot vs run loop);
- при необходимости получить adb logcat с момента импорта/запуска;
- локализовать точный native/Kotlin call site;
- не менять RM-217 firmware loader.

## 7. Границы устройств и архитектуры

- C3-00 / RM-614 использует только c3emu backend и эту ветку.
- Nokia 6300 / RM-217 остаётся отдельной целью и отдельной рабочей веткой.
- Нельзя переносить RM-614 flash map или предположения о boot в RM-217.
- Firmware остаётся пользовательской: не добавлять файлы прошивки в Git, APK или CI artifacts.
- Приложение не прошивает физический телефон; эмулируется гостевой Series 40 в Android.
