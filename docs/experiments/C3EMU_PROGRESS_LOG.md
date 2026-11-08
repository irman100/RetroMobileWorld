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


## 8. Фото-калибровка D-pad и нижней клавиатуры — 8 ноября 2026

Исходный эталон: `docs/75-205-263-02.png`, 968 × 1920. Все координаты ниже заданы относительно исходной фотографии. Рисуемый кружок и круговая hit-зона используют один и тот же центр; радиусы небольших клавиш не увеличивались.

### Навигационный ободок

Повторно определены середины именно металлического ободка, а не его внешнего края:
- UP: `(480, 1035)`;
- DOWN: `(480, 1226)`;
- LEFT: `(378, 1127)`;
- RIGHT: `(582, 1127)`;
- OK остаётся `(480, 1127)`.

UP/DOWN разнесены от внутренней OK-кнопки. LEFT/RIGHT сдвинуты внутрь от внешнего края и попадают на середины боковых частей хромированной рамки. Индексы матрицы и радиусы не менялись: направления 16 px, OK 20 px.

### Нижний буквенный ряд

Центры десяти физических колпачков уточнены по серединам между верхними и нижними тёмными разделителями, измеренным на вертикали каждого колпачка:

`(95.5,1557), (180,1574), (266,1587), (351.5,1598), (437,1602), (523,1603), (608,1599), (694.5,1589), (780,1577), (865,1561)`.

Получается реальная плавная дуга клавиатуры: края подняты, середина ниже. Верхние два буквенных ряда не менялись.

### Самый нижний ряд служебных клавиш

Центры также перенесены с надписей/символов на физические колпачки:
- Function: `(88,1668)`;
- Shift: `(176,1699)`;
- Car/Symbol: `(266,1716)`;
- Space: `(483,1735)`;
- пунктуация `:` и `/`: `(700,1717)`;
- запятая/точка: `(792,1699)`;
- Ctrl: `(881,1660)`.

### Ограничения проверки

Это замер геометрии по выбранному PNG, а не подтверждение на физическом телефоне. После сборки всё ещё надо открыть оболочку на телефоне и проверить поочерёдно D-pad, OK и нижние клавиши касаниями. Физическая калибровка не отмечается завершённой.

CI runs `42465042109` и `42465729769` подтвердили сборку native JNI для трёх ABI, но не запустили Android unit tests: Gradle Wrapper не смог загрузить Gradle 9.6.0 (timeout/HTTP 504). APK assembly и upload были пропущены. Для текущего коммита нужна свежая CI-проверка.


## 9. Touch input, IMAGE, LCD и host-loop — 8 ноября 2026

### Круговые области и реальные события клавиатуры

- Кружки стали малозаметными: заливка alpha=2/255, обводка alpha=24/255, толщина 1 px. Под ними остаются видны физические клавиши и подписи.
- Кружок и hit-zone используют один центр и один радиус в координатах исходной фотографии.
- Все зоны передают в native c3emu матричные индексы через `nativeKey(index, down)`: касание = key-down, отпускание = key-up.
- Матрица специальных клавиш вынесена в `C3KeyMatrix.kt`. Значения проверены по pinned upstream c3emu `web/app.js`: Contacts 38, Messaging 52, Function 50, Ctrl 27, D-pad 56–60, soft keys 29/43, Call 30, End 255 и нижние модификаторы.
- Добавлен `C3KeyPressLedger`: корректно обрабатывает скольжение между кругами, ACTION_CANCEL и несколько указателей на одной клавише. Key-up отправляется лишь после отпускания последнего указателя.
- Добавлены unit-тесты `C3KeyMatrixTest` и `C3KeyPressLedgerTest`: соответствие матрицы, короткое нажатие, скольжение, multi-touch и cancel.
- Реальное нажатие на физическом телефоне пока не проверено; это проверяется после сборки нового APK.

### LCD aperture

Прямоугольник экрана использует полные границы выреза исходной фотографии 968×1920: left=82, top=289, right=885 и bottom=899 как exclusive edges (прозрачные пиксели x=82..884, y=289..898). Кадр гостевой системы 320×240 растягивается на всю эту область и обрезается по контуру выреза. Проверка на скриншоте реального телефона всё ещё нужна.

### Импорт IMAGE и подбор региональной комплектации

- Файлы `*.image_*` — не обычные FPSX image regions MCUSW/PPM. Upstream `c3emu::content::parse` читает последовательность разреженных FAT16 C: блоков с маркером `54 01 30 15 00 03 00`, заголовком длиной 26 байт, BE length/address и boot sector по адресу 0.
- Kotlin inspector использует этот отдельный путь для `.image_*`, игнорирует префикс/заголовок до первого блока, проверяет все последовательные блоки, file-boundaries и FAT boot sector.
- Добавлен regression test для IMAGE с несколькими разреженными блоками; он также проверяет префикс с байтом `0x7B`, который ранее мог давать ошибку «unsupported FPSX block type 0x7B».
- MCUSW/PPM/IMAGE теперь подбираются по общему release stem и региональному suffix: MH1 сочетается только с MH1, MR — только с MR. Независимый выбор «последнего файла каждого типа» убран из boot path.
- Новый IMAGE устанавливается только при первом создании C: диска. Если C: уже сохранён в app-private storage, он намеренно не перезаписывается новым IMAGE. Импортёр показывает это пользователю явно.
- Большой firmware-файл передаётся в c3emu через JNI `nativeSetFileFromPath`: нативный слой читает уже импортированный app-private файл непосредственно в буфер `c3_alloc`, без промежуточных Kotlin `readBytes()`, JNI byte-array conversion и дополнительного memcpy.
- Файл пользователя в репозиторий/CI не добавлялся. Поэтому реальный `rm614__08.71.image_*` ещё нужно импортировать на телефоне; unit fixture подтверждает структуру, но не является проверкой полного пользовательского файла.

### Оптимизация цикла эмуляции

Найденные host-side задержки и изменения:
- Blocking `AudioTrack.WRITE_BLOCKING` ранее выполнялся на callback-пути native worker и мог задерживать следующую CPU slice и события клавиатуры. Теперь PCM передаётся на отдельный ограниченный audio executor.
- Native run budget согласован с upstream web worker: 16 ms.
- LCD frame доставляется не чаще одного раза за 15 ms, как в upstream; одинаковый кадр не копируется повторно.
- WFI host sleep ограничен 20 ms, чтобы длинная пауза не задерживала нажатия и обновление экрана.
- Dirty C: chunks записываются с throttle 1000 ms вместо 250 ms; остановка, ошибка и уход приложения в фон по-прежнему выполняют принудительный flush.
- Gradle/Android CI теперь использует `gradle/actions/setup-gradle@v6` с Gradle 9.6.0 и кэшем дистрибутива/зависимостей; сетевые 5xx/timeouts на задачах Gradle получают до трёх повторов. Wrapper networkTimeout повышен до 120000 ms.

Эти изменения убирают известные задержки host-интеграции и приводят cadence ближе к upstream c3emu. Они **не доказывают увеличение guest CPU speed или FPS**: upstream synchronizes guest clock to real time. Скорость и плавность надо замерить на физическом телефоне после установки.


## 10. CI checkpoint — 8 November 2026

Текущий кодовый checkpoint: `9c1112e856ac16d5799a471d33711f44b21efcdf`.

**GitHub Actions push run `42469546828` — SUCCESS.**
- Device profile/catalogue validation — success.
- Firmware-inspection Python tests — success.
- Android SDK / NDK setup — success.
- Rust Android target setup + `cargo-ndk` — success.
- Native c3emu JNI build: `arm64-v8a`, `armeabi-v7a`, `x86_64` — success.
- `:app:testDebugUnitTest` — success (includes IMAGE sparse multi-block fixture and C3 key matrix / key-press ledger tests).
- `:app:assembleDebug` — success.
- Artifact upload — success.

Artifact:
- name: `retro-mobile-world-c3emu-debug`
- artifact ID: `13613535664`
- archive SHA-256: `0e79171cda673b0ef73f40e7435a0c9e21ab04b80994c8855aaa2caf1fdd3884`
- APK path inside ZIP: `app-debug.apk`
- APK size: `12,494,146` bytes
- APK SHA-256: `e5f08448acb424aeceb4a79c6a2d8e08b1b533cafb61b81d06923d88d1927f9c`
- artifact expiry: 22 November 2026, 10:03 UTC.

CI dependency fix:
- `settings.gradle.kts` now uses a content-filtered WSO2 Maven Central mirror only for `org.jetbrains.kotlin:kotlin-compiler-embeddable`; other dependencies still use their normal Google/Maven Central/Plugin Portal repositories.
- This resolved the repeated HTTP 504 on Kotlin compiler 2.2.10.
- The first successful Kotlin compile exposed a test fixture typing error (`Int` vs `Byte` at FAT boot signature byte `0xAA)); the fixture now explicitly uses `.toByte()`, and all unit tests pass.

The PR-triggered duplicate run `42469551102` has also completed native build and unit tests successfully; at this journal update it is still executing APK assembly. The successful push run above already produced and uploaded the verified debug APK.

**Phone-only checks still pending:** installation on the user's physical phone, import of the actual `rm614__08.71.image_*`, first-boot C: content, real Series 40 frame/home screen, touch response on every hotspot, LCD aperture screenshot, audio, and measured FPS/input latency. CI does not prove those device-level behaviours.


## 11. Green CI checkpoint — exclusive Kotlin compiler mirror — 8 November 2026

Current branch head: `4c00850ea09fb681257d303d6e01f03ef2a763aa`.

The previous Kotlin compiler download failure was a real Maven-to-GitHub redirect to a GitHub Releases URL returning HTTP 504. The first WSO2 repository declaration was content-scoped, but Gradle still resolved the module through Maven Central in the failing run. The repository policy is now explicit in `settings.gradle.kts`:
- `exclusiveContent` binds only `org.jetbrains.kotlin:kotlin-compiler-embeddable` to WSO2;
- the mirror resolves Maven POM + artifact metadata (no Gradle-module metadata redirect);
- all other dependencies retain normal Google/Maven Central/Plugin Portal sources;
- Kotlin / Android plugin versions were not changed.

**Latest push CI: run `42472380211` — SUCCESS.**
**Latest PR CI: run `42472383487` — SUCCESS.**

Both runs on the exact same head `4c00850` completed every step:
- profile/catalogue validation;
- firmware-inspection Python tests;
- Android SDK / NDK setup;
- Rust targets + cargo-ndk;
- native c3emu JNI build for arm64-v8a, armeabi-v7a and x86_64;
- `:app:testDebugUnitTest`;
- `:app:assembleDebug`;
- debug APK artifact upload.

Artifact:
- name: `retro-mobile-world-c3emu-debug`;
- artifact ID: `13614395927`;
- ZIP size: 7,935,562 bytes;
- ZIP SHA-256: `c3d85b501a1c1483707c029df3d8e2c8b2112b9caa9001334782aa9b3198b42e`;
- GitHub Actions run: https://github.com/irman100/RetroMobileWorld/actions/runs/42472380211;
- expires: 22 November 2026, 10:43 UTC.

### LCD aperture exact pixels

The selected 968×1920 shell PNG contains a fully transparent rectangular screen aperture at x=82..884 and y=289..898 inclusive. The exclusive-edge geometry in `C3DeviceView` is therefore exactly `left=82, top=289, right=885, bottom=899`. The 320×240 emulator frame is mapped to the whole rectangle, clipped to the aperture. This confirms the source-image geometry; the final Android screenshot still needs visual verification on the phone.

### Still pending on the physical phone

- Install the current green APK without uninstalling or clearing app-private data.
- Import the actual user's RM-614 MCUSW/PPM/IMAGE files and verify the real IMAGE is accepted.
- First boot must seed factory C: only if there is no existing saved C: image.
- Confirm a real Series 40 LCD frame/home screen.
- Tap and hold every physical touch point, including release, slide and multi-touch.
- Check LCD fit on the device, sound, C: persistence, and measure frame/input latency.

CI proves source compilation, native ABI build, unit tests and APK assembly only. It does not prove the user's actual firmware boots or that touch/LCD/audio are correct on the handset.


## 12. Первый подтверждённый запуск RM-614 и отзывчивость клавиатуры — 8 ноября 2026

### Результат на физическом телефоне

Пользователь подтвердил:
- выбран Phoenix ZIP для RM-614;
- ZIP импортировался и запустил полноценную Nokia Series 40;
- после загрузки пользователь смог перейти к меню и попробовать запускать разные приложения;
- при этом часть touch-клавиш субъективно не реагировала, а переходы/запуск приложений выглядели заторможенными или зависающими.

Это первый подтверждённый пользователем запуск полноценной гостевой прошивки C3-00 на телефоне. Это не следует путать с завершённой проверкой всех физических клавиш, FPS или стабильности приложений: эти проверки остаются открытыми.

### Найденная причина потери коротких нажатий

До этого исправления `sendMatrixKeyAsync` ставил key-down и key-up двумя независимыми задачами в executor. Если короткий тап полностью происходил между двумя CPU slices, native worker мог обработать down и up подряд до следующего `c3_run`. Гостевой CPU в таком случае вообще не видел клавишу нажатой.

### Исправление в коммите `51e60a11dc5b1c4be1eb20cff0324c26ce81c086`

- Добавлен `C3MatrixInputQueue`: key edges принимаются потокобезопасно и применяются непосредственно перед очередным native CPU slice. Быстрый тап удерживает клавишу как минимум на один полный slice; отпускание происходит на следующей границе цикла.
- Очередь корректно отменяет отложенное отпускание при повторном нажатии и сбрасывает удерживаемые клавиши перед остановкой native-машины.
- Touch hit-radius увеличен до `max(radius * 2.3, 30)` в фото-координатах. Сами нарисованные едва заметные маркеры не увеличивались, их видимость не изменилась.
- Activity больше не делает main-thread round trip после каждого 16 ms native slice. Вместо этого один непрерывный цикл выполняется на единственном native owner thread, по модели upstream c3emu web worker.
- Для LCD обновлений используется coalescing: если UI не успевает отрисовать очередной кадр, сохраняется только последний кадр, устаревшая очередь не накапливается.
- Добавлена двухсекундная диагностика Logcat с тегом `C3EmuPerf`: cycleHz, deliveredFps, среднее/максимальное время цикла и upstream `c3_stats()`.

### Проверка

**GitHub Actions run `42475334037` — SUCCESS** для коммита `51e60a1`.
- Native c3emu JNI libraries: arm64-v8a, armeabi-v7a, x86_64 — success.
- Android `:app:testDebugUnitTest` — success, включая 4 новых теста очереди коротких нажатий и отпускания клавиш.
- Android `:app:assembleDebug` — success.
- Debug APK upload — success.

Артефакт:
- artifact ID: `13615556057`;
- ZIP SHA-256: `9e6cf0f7eeb7cbdf55611a97576cbd9cc1820e4a546ebb743b93d3c496ffb531`;
- APK size: 12,494,146 bytes;
- APK SHA-256: `d5ccb5e691afd1b9c0ae886c8fd314bb19bd14cc0bb3d18775f0fe063dbc0a30`;
- run: https://github.com/irman100/RetroMobileWorld/actions/runs/42475334037;
- artifact expires 22 November 2026 11:23 UTC.

### Что ещё нельзя считать доказанным

Новый код скомпилирован и прошёл unit tests, но пока не установлен и не измерен на физическом телефоне. Поэтому фактический прирост FPS/скорости не заявляется. Следующий device checkpoint:
- установить новую сборку поверх существующего приложения, не очищая данные и не удаляя C:;
- отдельно проверить короткие нажатия, удержание, D-pad, soft keys и клавиатуру;
- собрать `C3EmuPerf` Logcat после загрузки, переходов меню и запуска приложения;
- по cycleHz/deliveredFps/native stats определить, где остаётся предел: CPU interpreter, LCD refresh, audio или app launch/storage.



## 13. Перенос LCD frame conversion с UI-потока — 8 ноября 2026

После перехода на непрерывный native worker осталось ещё одно измеримо тяжёлое действие на UI-потоке: `C3DeviceView.showFrame()` вручную конвертировал все 320×240 RGBA пикселей в ARGB и обновлял Bitmap при каждом кадре. Это могло задерживать обработку касаний даже при быстром CPU worker.

Изменение:
- Добавлен отдельный single-thread executor `c3emu-frame-render`.
- Последний готовый guest frame преобразуется и передаётся в Bitmap на frame worker, не на main/UI thread.
- Coalescing последнего кадра сохранён: render executor не получает бесконечную очередь старых frame объектов.
- Итоговый короткий Bitmap copy синхронизирован с `onDraw`; затем вызывается `postInvalidate()` для UI.
- Audio и native CPU остаются на отдельных потоках; native c3emu по-прежнему вызывается только на одном owner thread.

Это целевая оптимизация отзывчивости интерфейса, но реальный input-to-screen latency/FPS требуется повторно проверить на телефоне. Не заявлять прирост до анализа `C3EmuPerf` и тестов ввода.



## 14. Финальная калибровка верхних/нижних клавиш, Android haptics и диагностика boot splash — 8 ноября 2026

- Первый и второй ряды QWERTY изменены на умеренную симметричную кривую вниз к крайним клавишам: крайние центры теперь на 6 photo-px ниже центра ряда. Preview overlay проверен на исходной фотографии; точки не заходят к нижнему краю клавиш. Третий ряд Z…Enter оставлен без изменений по просьбе пользователя.
- Две крайние кнопки нижнего ряда (Function и Ctrl) смещены внутрь к центру телефона на 16 px в исходной photo-space системе координат; остальные нижние клавиши не тронуты.
- Добавлен короткий системный `HapticFeedbackConstants.KEYBOARD_TAP` на фактический key-down edge. Нет вибрации на key-up или на повторном указателе, удерживающем уже нажатую клавишу; стандартная настройка Android для тактильной отдачи остаётся уважаемой.
- Причина обрыва заставки Nokia Connecting People пока не подтверждена. Upstream c3emu в interpreter режиме синхронизирует гостевые часы с wall clock и может пропускать кадры низкоприоритетных анимаций, когда host CPU отстаёт; это возможная гипотеза, не установленный диагноз.
- Добавлен терминальный Logcat тег `C3EmuBoot`: при остановке гостевой ОС пишет причину и итоговые native stats; при исключении цикла пишет ошибку. Существующая периодическая `C3EmuPerf` запись каждые 2 s включает guest frame counters (`frames/changed/idle`) и host cycle/FPS данные, чтобы различить падение ОС и незавершившуюся LCD-анимацию.
- Физический тест этой калибровки, haptic feedback и boot splash остаётся обязательным после установки. Пока не заявлять, что анимация исправлена.



## 15. Разворот дуги первых двух рядов QWERTY — 8 ноября 2026

По обратной связи пользователя предыдущая калибровка первых двух рядов была направлена неверно. Исправлена именно ориентация дуги зеркальным отражением Y-координат относительно горизонтали через две центральные клавиши каждого ряда:
- Ряд 1: `[1339, 1341, 1343, 1344, 1345, 1345, 1344, 1343, 1341, 1339]`.
- Ряд 2: `[1466, 1467, 1469, 1471, 1472, 1472, 1471, 1469, 1467, 1466]`.
- Центральные две клавиши обоих рядов остались на прежних координатах.
- Затронуты только 16 внешних точек первых двух рядов; третий ряд Z…Enter, нижний ряд модификаторов, touch hit-radius и haptic feedback не менялись.
- Требуется повторная проверка фото-совмещения и на физическом телефоне после установки APK.



## 16. Повторное выравнивание клавиатуры, тактильная диагностика, RTC и устойчивость — 8 ноября 2026

- По исходной фотографии 968×1920 заново измерены границы верхних двух рядов. Предыдущие центры были сдвинуты вниз примерно на 8–20 photo-px; теперь центры стоят посередине физических колпачков, а дуги следуют форме рядов.
- Невидимые области первых двух рядов стали эллипсами 40×52 photo-px: увеличена площадь уверенного касания при сохранении зазора между соседними клавишами. Третий ряд Z…Enter и четвёртый ряд модификаторов не менялись.
- Android View явно включает haptic feedback и пишет в Logcat тег C3EmuHaptics факт срабатывания KEYBOARD_TAP. Глобальная настройка тактильной отдачи Android продолжает соблюдаться.
- Геометрия корпуса, LCD-матрица и clip path теперь кэшируются при изменении размеров View, а не пересоздаются при каждом LCD-кадре. Инвалидация экрана синхронизирована с ближайшим display frame через postInvalidateOnAnimation().
- Host clock sync теперь работает не только на начальной загрузке: Activity отслеживает смену времени, часового пояса и минутный tick; запрос передаётся в native owner thread, где обновляются семь PMU RTC регистров работающей гостевой системы. Это учитывает локальную дату/время и DST offset Android.
- c3emu-web wrapper зафиксирован локально поверх upstream rev 9b0d3ae2da326e109428f64d8760ea823ee1b648. Panic в boot/run теперь перехватывается внутри Rust C ABI, преобразуется в понятную диагностику; только аварийная volatile machine session сбрасывается, persistent flash не очищается. Обычный OS fatal/unsupported путь остаётся явным и доступен для ручного перезапуска.
- Проверка upstream показала, что реального сетевого адаптера/модема и packet backend пока нет: SIM моделируется как отсутствующая, ISI модуль только наблюдает/логирует внутренние сообщения. Поэтому Интернет в этой сборке не заявляется; для него нужна отдельная реализация виртуального SIM/modem, GPRS/ISI packet transport и Android network bridge.
- CI и физические тесты этой версии обязательны до выдачи APK. На телефоне отдельно проверить выравнивание/все клавиши, реальный haptic, синхронизацию RTC после смены часового пояса, повторный запуск после OS fatal и FPS/input latency.
