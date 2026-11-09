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


## 17. Физическая обратная связь после сборки 15a6a5c — 8 ноября 2026

### Подтверждено пользователем
- Положение точек/центров верхних клавиш теперь исправлено и на устройстве совпадает с нужными клавишами. Эту геометрию не переделывать без отдельной просьбы.
- Третий буквенный ряд и нижний ряд модификаторов остаются вне дальнейшей калибровки.

### Что оказалось неработающим
- Физической тактильной отдачи на нажатие нет, несмотря на прежний вызов `View.performHapticFeedback(KEYBOARD_TAP)`. Следующая правка использует прямой Android `Vibrator` с коротким платформенным `VibrationEffect.EFFECT_CLICK`, проверяет глобальную настройку HAPTIC_FEEDBACK_ENABLED и добавляет разрешение `VIBRATE`. Это пока кодовая правка; её эффект ещё необходимо подтвердить на телефоне.
- Гостевая дата/время не совпадает с основным Android-устройством. RTC update path был добавлен ранее, но не считается работающим только по наличию кода. Добавлены логи host timestamp/timezone и guest PMU RTC snapshot в native stats; нужно сравнить их после следующей установки.
- При запуске приложений, использующих сеть, гостевая эмуляция иногда зависает или останавливается. Точный stop reason ранее не был сопоставлен с действием пользователя.

### Стабильность и интернет — фактическая граница
- Rust panic containment в boot/run защищает Android-процесс от части native panic, но не превращает неизвестную/неподдержанную гостевую операцию в корректную. Неизвестные ARM/SoC действия нельзя молча пропускать без риска повреждения состояния гостевой ОС.
- В закреплённом c3emu отсутствует виртуальный модем, сетевой packet transport и мост к Android Connectivity. SIM модель представляет отсутствие карты; ISI слой наблюдает внутренние сообщения и не передаёт сетевые пакеты. Поэтому настоящий Интернет в текущем движке отсутствует.
- Для локализации зависания добавлены в `c3_stats` поля `stopped`, `host_epoch`, `rtc` (секунды, минуты, часы, weekday, день, месяц, год-2000), `sim_timeouts`, `sim_status` и `isi_messages`. При аварийном сбросе native machine теперь также сохраняется stop reason в статистике.
- Следующий диагностический запуск должен одновременно проверить вибрацию, часы и воспроизвести один проблемный сетевой запуск; один Logcat файл с тегами `C3EmuHaptics`, `C3EmuClock`, `C3EmuBoot`, `C3EmuPerf` позволит понять, что именно произошло.

Не заявлять, что вибрация, синхронизация часов, реальный Интернет или устойчивость гостевых сетевых приложений уже проверены на физическом телефоне.


## 18. Internet architecture discovery — WLAN-first — 8 November 2026

### Hardware/firmware evidence found
- The Nokia C3-00 user guide describes WLAN Internet access, scan/refresh, connect, saved networks and per-app connection policy (Ask first / Do not ask).
- The same user guide documents GPRS packet data with on-demand and always-online modes.
- The RM-614 service schematic shows a dedicated WLAN signal group: enable, IRQ, clock, MOSI/MISO, chip-select and clock-request, with SDIO aliases on the same lines.
- This means the device has a genuine WLAN hardware path in the target design; a modem-only emulation plan would have missed the more direct first target.

### Codebase gap confirmed
- Pinned c3emu has no WLAN/SDIO controller model, wireless MAC, virtual AP, IP packet endpoint or Android bridge.
- Its SIM controller explicitly behaves as no card and its ISI hooks are observers/counters, not a virtual modem.
- INTERNET permission alone cannot make the guest operating system network-capable.

### Libraries reviewed
- smoltcp is a Rust TCP/IP stack with Ethernet and raw/TAP device interfaces; it can help with a virtual NIC/test harness, but is not a WLAN/SDIO emulation.
- lwIP offers raw/sequential/BSD socket APIs, but likewise is not a WLAN controller.
- Linux Phonet documentation clarifies the cellular modem IPC/RPC packet layer; it is not an IP stack.
- Android VpnService can provide a TUN interface with explicit user consent; protect(Socket) can keep bridge sockets on the underlying Android network and avoid VPN routing loops.

### Decision and next steps
- Prioritize WLAN controller emulation and Android TUN/socket bridge first; defer GPRS until exact ISI traffic is captured.
- Before creating a fake chipset, inspect the exact user-supplied RM-614 firmware and extract driver/device IDs, initialization register writes, GPIO/SPI/SDIO sequence and packet-buffer format.
- This commit exposes recent ISI TX/RX messages and first-touch SoC/peripheral MMIO addresses in native stats.
- Acceptance is end-to-end: scan virtual AP, associate, configure IP, DNS, TCP/HTTP, HTTPS, Browser downloads .jad and .jar, verify stored files and launch a MIDlet.

### Firmware attachment availability
The user indicated that firmware was supplied in the preceding chat. No firmware archive is attached to this conversation runtime, and a search of the current conversation and the first 100 Library ZIP entries found no RM-614/Phoenix/MCUSW/PPM/IMAGE file. Do not claim firmware inspection has happened. The exact Phoenix ZIP must be re-attached in this chat before driver disassembly and firmware-specific protocol mapping.


## 19. Исправление CI после добавления меню выбора телефона — 8 ноября 2026

Первый CI для коммита 8156a2d собрал native JNI libraries для трёх ABI, но Kotlin compile остановился на новом обработчике выбора телефона: C3EmuActivity.showMessage принимает одну строку, а новый вызов передавал две. Это ошибка реализации меню, а не ошибка сетевого/native кода.

Исправление: сообщение о неподдерживаемом RM-217 теперь передаётся одним аргументом. После исправления требуется повторить push и PR CI, включая Kotlin compile, unit tests и APK assembly. APK от 8156a2d не создавался и не выдавался.


### MMIO diagnostic window refinement — 8 November 2026

The native stats register-touch window now returns the most recently first-touched 64 unique SoC/peripheral MMIO addresses rather than the earliest 64 from boot. This is more useful when the guest reaches WLAN settings after startup: newly touched controller registers are no longer hidden behind early boot activity. Existing ISI recent-message ring remains separate. This is still observational telemetry, not a controller implementation.


## 20. Specific WLAN module identified from RM-614 service references — 8 November 2026

A second research pass identified the physical connectivity component more precisely:
- Board reference N6300 is listed as Nokia part 4390090, BOB1.0M-a, LGA130.
- W6000 is the internal WLAN antenna and Z6300 is the WLAN RF band-pass filter.
- N6300 presents enable, IRQ, CLK_REQ and shared SPI/SDIO pins to the Juno host. The schematic labels the signals as both SPI and SDIO alternatives.
- N6300 also carries Bluetooth UART/PCM and FM/I2C connections, confirming the combined radio-module role.

This substantially narrows firmware inspection: locate the BOB1.0M-a WLAN driver, its power/clock/IRQ setup, and its actual SPI/SDIO protocol. A related but distinct BOB1.0M-b module in other Nokia devices is documented with TI WL1271; do not assume the RM-614 BOB1.0M-a is WL1271 without direct confirmation.

Sources and links are recorded in `docs/experiments/C3EMU_NETWORK_RESEARCH.md`. The exact Phoenix firmware still is not present in the current conversation/Library, so no disassembly has yet been performed.

The latest firmware inventory search paginated through 383 Library ZIPs plus the RAR inventory; no RM-614/Phoenix/MCUSW archive was found. The earlier-chat attachment must be attached again to this conversation before its bytes can be examined.


## 21. Анализ WLAN-драйвера по точной прошивке RM-614 — 8 ноября 2026

Пользователь повторно приложил Phoenix ZIP. SHA-256 архива: `5239534da21345e5fc7bc1fbe5277f7d25a7798e9196742fcc77dc5d34445b11`. В архиве есть MCUSW 08.71 (29 271 181 байт), региональные PPM/IMAGE и Phoenix metadata. Исходный архив не менялся; бинарные файлы прошивки в GitHub не копировались.

**Подтверждено статическим анализом MCUSW:**
- Broadcom S60 WLAN HAL/PDD `BcmWha` (около `0x003F7532`).
- Явная строка конфигурации `802.11n gSPI` (около `0x003F9EEA`).
- `WlanSpiaImpl` реализует Configure/Request/SPI DFC/ISR callbacks; `WlanHpaImpl` управляет PowerOn/PowerOff и IRQ. Это подтверждает выбранную прошивкой транспортную ветку SPI/gSPI и отдельный слой питания/прерываний.
- В стеке присутствуют Broadcom SDPCMD CDC, SDPCM, BDC, LMAC scan/join/reset, FIFO и RX/TX/interrupt handling.
- Встроенная строка device firmware `4.218.227.19` (build 12 Oct 2010). WHA содержит стадии INIT1/2/3, ожидание ALP/HT, `DevDownload`, проверки IDB code/NVMEM sections и verify code/vars. Это указывает на скачивание firmware и NVMEM в WLAN-чип при инициализации; точный IDB payload пока не извлечён.
- В тестовом интерфейсе встречаются профили `WLAN_TEST_EEPROM_NVS_BOB_B1_0` и `...BOB_T1_0`; связь конкретного профиля с N6300 BOB1.0M-a ещё не доказана.

**Вывод о шине:** схема RM-614 показывает WLAN_SPICSX, WLAN_SPITxData, WLAN_SPIRxData, WLAN_SPIClk, WLAN_IRQ, WLAN_En и WLAN_CLK_REQ. В сочетании с gSPI-конфигурацией и WlanSpiaImpl это подтверждает SPI/gSPI как используемый firmware host path. SDIO-строки в других подсистемах MCUSW не доказывают SDIO-транспорт WLAN.

Точный кристалл и ревизия N6300/BOB1.0M-a остаются открытыми. Строка `BCM4329B1 Class 1.5 For Nokia Phones` находится внутри отдельного WMV3/Windows Media блока и не используется как доказательство маркировки чипа. Не подменять модуль TI WL1271 от другой версии BOB.

Следующий шаг — определить IDB code/NVMEM payload, снять runtime SPI/IRQ трассу при сканировании WLAN, затем реализовать минимальную наблюдённую gSPI state machine и packet path. Проверка сети остаётся незавершённой до реального скачивания JAD/JAR встроенным Series 40 Browser и запуска MIDlet.


## 22. Проверка SPI/MMIO-границы закреплённого c3emu — 8 ноября 2026

Проверен upstream `androettop/c3emu@9b0d3ae2da326e109428f64d8760ea823ee1b648`.

- `bus.rs` отображает окна DMAC, LCDC, SOC_IO и PERIPH; обработчики чтения/записи включают SoC, I2C, LCD, SIM, DMAC, keypad, timer, GPIO, RTC и DSP. SPI-master, SDIO-host и WLAN target отсутствуют.
- Необслуживаемые MMIO-регистры читаются/записываются через sparse backing store: значения могут запоминаться, но SPI transfer/FIFO/CS/clock/IRQ side effects отсутствуют. Следовательно, нужен не только Broadcom gSPI target, но и отдельная эмуляция SPI-master со стороны Nokia SoC.
- Текущий GPIO моделирует статусные защёлки и IRQ line 6, но не общий вывод/состояние физических линий WLAN enable, CLK_REQ и IRQ.
- `c3_stats.io_touches` показывает последние 64 уникальных адреса. В upstream `State::touch` сохраняет только первое обращение к каждой паре region/address — это помогает обнаружить адрес, но не раскрывает повторные транзакции.
- `Machine::iolog(addr,len)` позволяет логировать каждое чтение/запись выбранного диапазона вместе со значением и PC; общий event log ограничен 20 000 записями и выводится через `Machine::report()`. Android C ABI/JNI пока не предоставляет включение диапазона и выгрузку событий.

Следующий технический шаг: после обнаружения кандидатов через first-touch добавить opt-in ограниченную трассу выбранных MMIO-диапазонов. Затем определить реальную конфигурацию SPI-master, CS/CLK, WLAN enable, CLK_REQ и IRQ; реализовать сначала host peripheral и разводку сигналов, затем Broadcom gSPI target и только после этого packet bridge. Адреса и значения регистров не угадывать.


## 24. Исправление формата MMIO-журнала — 8 ноября 2026

В предыдущем тесте длинная строка `C3EmuPerf` включала полный JSON native stats. Большая карта ISI-счётчиков и текстовые ISI-сообщения переполняли лимит записи Android Logcat, из-за чего хвост с MMIO-адресами обрезался.

Теперь вывод разделён:
- `C3EmuPerf` содержит только cycleHz/deliveredFps/cycleAvgMs/cycleMaxMs.
- `C3EmuTrace` содержит отдельный компактный JSON с максимум 40 последними уникальными first-touch адресами SoC/peripheral.
- Запись адреса имеет вид `[region, op, addr, value, pc]`; `s` — SoC, `p` — периферия; HEX-поля — восемь цифр в верхнем регистре.
- Поля running/stopped/simTimeouts/isiMessages помогают связать трассу с остановкой гостевой ОС. Stop text ограничен 160 символами.
- Добавлен Rust unit test на максимум 3072 байта и сохранение последних 40 адресов; CI запускает его перед Android ABI сборками.

Это исправляет обрезание диагностической строки. Важно: список остаётся first-touch, то есть не содержит повторные чтения/записи SPI-регистров и их временной порядок. Полная access-by-access трассировка не заявляется в этой правке.

Команда съёма:
```powershell
adb logcat -v time -s C3EmuPerf:I C3EmuTrace:I C3EmuBoot:E |
    Tee-Object "$env:TEMP\\emulator-mmio-trace.txt"
```


## 25. Исправление первой ошибки CI для компактной трассы — 8 ноября 2026

Push CI №489 дошёл до нового шага `cargo test`, но компилятор сообщил о незакрытом модуле `android_wrapper_tests` в конце `engine/c3emu-web-android/src/lib.rs`. Это синтаксическая ошибка вставки теста: отсутствовала закрывающая фигурная скобка модуля. Она добавлена. Сетевой и эмуляционный runtime-код этим исправлением не менялся. Повторный CI обязателен до выдачи APK.


## 26. SPI host register window found in device capture — 9 November 2026

Новый лог показывает, что гостевая ОС не перезапускает Android-приложение: PID 1192 сохраняется; running=true до 13:54:46, после чего в 13:54:48.436 появляется BB H/W watchdog reset из PC 0x80702931.

В 13:54:44.918 впервые появились адреса SoC MMIO 0x088D0000, 0x088D0004, 0x088D0014, 0x088D0020, 0x088D103C. В следующем интервале появились 0x088D0010, 0x088D0024, 0x088D0018, 0x088D000C.

Прошивка MCUSW 08.71 разобрана по точным PC из журнала. Инструкции на этих адресах принадлежат SPI host-драйверу: рядом находятся строки SPI_Init, ConfigSPISelectMux, SPI_Set_Clock, IOCR2_REG, IOCR0_REG. Это подтверждает, что прошивка начинает настройку SoC SPI controller непосредственно перед watchdog. Сам watchdog пока нельзя однозначно приписать одному регистру: текущий first-touch не показывает повторное polling и IRQ.

В текущий диагностический build добавляется адресная access-by-access трасса через Machine::iolog(0x088D0000, 0x2000). Тег C3EmuBus выдаёт последние 24 SPI MMIO transactions с icount, шириной операции, address, value и guest PC; строка ограничена 3072 байтами и покрыта Rust-тестом. Это только инструментирование, не модель SPI-контроллера.

## 27. Repeated SPI status polling and global trace saturation — 9 November 2026

The next device capture supplied by the user contains repeated one-byte reads of SoC MMIO address `0x088D000C`:

- operation: `R1`;
- returned value: `0x00`;
- guest PC: `0x8073A838`;
- consecutive recorded reads are separated by 8 guest instruction counts;
- the last 24 records retained in the compact output show this same read pattern;
- the guest eventually reports `BB H/W watchdog reset` at 14:26:06.841.

This is direct evidence that the firmware entered a repeated polling/read path at `0x088D000C` and that the current bus model returned zero for the recorded reads. It does **not** yet prove which status bit is expected, what branch condition should terminate the loop, or that the same polling continued uninterrupted until the watchdog. The PC at `0x8073A838` must be decoded together with its load, test and branch instructions before assigning register semantics.

### Why the trace stopped growing at 13,868 SPI records

The compact C3EmuBus function reads `State.events` and filters that shared historical vector for the selected SPI MMIO range. It does not own a separate rolling buffer.

The pinned upstream source confirms:

- `MAX_EVENTS = 20000`;
- `State::event()` appends only while `events.len() < MAX_EVENTS`;
- once the shared vector reaches the cap, new events are silently not appended;
- `c3_bus_trace_stats()` snapshots that same vector and `compact_bus_trace_json()` filters it down to SPI events and retains the last 24 matching rows.

Therefore, a stable filtered `eventCount=13868` is consistent with saturation of the shared 20,000-entry event vector by SPI and non-SPI events. The current JSON does not expose the total vector length or a saturation flag, so the precise split is not recorded. The unchanged 24 rows are the last retained SPI records, not proof that the loop stopped or that it remained unchanged through the watchdog.

### Exact source-level reason for the zero read

In the pinned upstream `bus.rs`, a read first asks the implemented SoC/I2C/LCD/SIM/DMAC/keypad/timer/GPIO/RTC handlers and then falls back to the sparse `Backing` store. For an address not claimed by a device model, an uninitialized byte reads as zero. Writes update the sparse byte store, but they do not trigger SPI transfer, FIFO, chip-select, clock, DMA or WLAN interrupt behavior. The `0x088D0000..0x088D1FFF` window is currently unclaimed by an SPI-master model.

This explains the recorded zero at `0x088D000C`; it is not evidence that zero is the correct hardware reset/status value.

### Required next engineering work

1. Replace reliance on the global 20,000-entry `State.events` vector with a dedicated rolling SPI-MMIO ring buffer at the MMIO access point. It must continue collecting new records after the general diagnostic list fills and expose dropped/overwritten counts.
2. Disassemble the MCUSW code around guest PC `0x8073A838`, record the exact load width, register destination, mask/test and branch target, then identify the expected status transition.
3. Capture a fresh trace with the ring buffer and correlate writes, repeated status reads, DMA/FIFO activity and interrupt state.
4. Only after the register behavior is mapped, implement the minimum Nokia SoC SPI-master and board signal behavior; then connect the Broadcom gSPI target.
5. Keep guest Internet marked unsupported until the Series 40 Browser completes a real JAD/JAR download and launch test.

No SPI register value or status bit has been guessed, and this capture does not itself add Internet functionality.

## 28. Do not conflate Broadcom target offsets with Nokia SoC MMIO — 9 November 2026

Web research found Broadcom gSPI family datasheets where target-side address `0x000C` is an internal F1-info register and where the target's 32-bit status word reports data readiness, FIFO errors, F2/F3 interrupts, RX-ready and packet length. These are registers accessed *inside the Broadcom device through gSPI*.

The guest log address `0x088D000C` is different: it belongs to the Nokia SoC `SOC_IO` host-controller window. The same low offset does not mean it is Broadcom F1-info. Do not transfer Broadcom status bits or reset defaults to the Nokia host register.

The BCM4330/BCM4334 documents help outline a plausible Broadcom gSPI protocol family, but they do not identify the exact silicon inside N6300 / BOB1.0M-a. Keep that silicon and NVS/IDB mapping unresolved.

A public search for the exact Nokia addresses and `IOCR0_REG` / `IOCR2_REG` did not find an authoritative host SoC register manual. An ARM "Juno Development Platform" TRM surfaced for the word Juno, but it is a separate ARM reference platform and is explicitly excluded from this investigation.

References and the protocol-family caveat are recorded in `docs/experiments/C3EMU_NETWORK_RESEARCH.md`.

## 29. RM-614 WLAN board signals verified from schematic — 9 November 2026

The RM-614 schematic page 7 confirms the N6300 WLAN interface wiring: `WLANENABLE` on H2, `WLAN_IRQ` on K3, `SPI_CLK` on K2, `SPI_MOSI` on H3, `SPI_MISO` on L1, `SPI_CSX` on K4, and `CLK_REQ` on K1. The same data/control pads are shown with SDIO alternate-function names (`SDIO_CLK`, `SDIO_CMD`, `SDIO_D0`, `SDIO_D3`, and `SDIO_D1/WLAN_IRQ`).

The repair/BOM listing maps board component N6300 to Nokia part 4390090, description BOB1.0M-a LGA130. The listing does not establish the WLAN die manufacturer or exact silicon model. Keep the current MCUSW evidence for Broadcom gSPI, and do not infer a BCM4329/4330/4334 part number from the module label.

Source URLs and the pin map are recorded in `docs/experiments/C3EMU_NETWORK_RESEARCH.md`.

## 30. Rolling SPI-MMIO event ring independent of upstream cap — 9 November 2026

### Runtime implementation

Commits:
- `7c3b684dc49209e7ecb088628b2947dfffe11666`: add a dedicated SPI event ring and drain the upstream event batch.
- `6b225126208c519788cce58f47dfee9f578b3dcd`: correct a test counter type found by the first CI run.
- Documentation and evidence boundary are incorporated in branch head `b430fbcefff162a440100feec7f7b650b86e0b4d`.

The Android c3emu wrapper no longer waits for the upstream shared event vector to fill. It runs the deterministic interpreter in bounded slices of at most 8,192 guest instructions, then drains `State.events` into an independent 24-record SPI ring. This keeps the host-side general `MAX_EVENTS=20,000` ceiling from silently freezing the C3EmuBus output. CPU register values, MMIO behavior, guest timer model and guest-visible instruction ordering are not modified by the diagnostic drain; only the host execution slice boundary is more frequent.

The C3EmuBus JSON now includes:
- `eventCount`: lifetime number of observed accesses in the selected SPI window;
- `bufferedCount`: number currently retained (up to 24);
- `overwritten`: number of older SPI rows displaced from the rolling window;
- `otherEventCount`: non-SPI events drained from upstream's shared vector;
- `events`: the most recent rows with guest instruction count, operation/width, address, value and guest PC.

The ring and counters are cleared on each fresh boot. The final trace remains readable after a guest stop. `C3EmuTrace` first-touch diagnostics are unchanged.

### Test and CI evidence

A new Rust unit test builds a synthetic 20,000-event upstream batch containing 13,868 SPI reads at `0x088D000C` and 6,132 unrelated events. It verifies that:
- the shared batch is fully drained;
- all 13,868 SPI transactions are counted;
- all 6,132 unrelated events are accounted for;
- exactly 24 SPI rows remain buffered;
- 13,844 older SPI rows are counted as overwritten;
- the latest retained row is the last event in chronological order;
- serialized output remains within 3,072 bytes.

The first CI exposed only a `u64` versus `usize` assertion mismatch in this test. The test type was corrected, and the subsequent push/PR workflows both passed:

- Push CI #510: https://github.com/irman100/RetroMobileWorld/actions/runs/42617527731
- PR CI #511: https://github.com/irman100/RetroMobileWorld/actions/runs/42617533834
- Both passed the bounded MMIO test, native JNI builds for arm64-v8a / armeabi-v7a / x86_64, Android unit tests, debug APK assembly and artifact upload.

### APK for physical-device validation

- Artifact ID: `13672779512`
- Artifact name: `retro-mobile-world-c3emu-debug`
- APK file: `app-debug.apk`
- APK size: `12,715,047` bytes
- SHA-256: `12b494c099293fa70eba3d586a55fe821b72a09e6ce69f0fe8085fa7e730c82b`

This build is diagnostic only. It has not yet been installed or verified on the physical phone, and it does not implement the SoC SPI master, Broadcom WLAN target, or Internet packet bridge. Compare `C3EmuPerf` with the preceding build because the more frequent host slice/drain may add diagnostic overhead. The next required result is a fresh C3EmuBus trace showing whether `0x088D000C` polling continues through the watchdog and whether any other register/IRQ events appear.

## 31. RM-614 MCUSW polling code decoded at 0x8073A838 — 9 November 2026

### Firmware container and runtime address mapping

The user re-attached the original Phoenix archive. Its SHA-256 is
`5239534da21345e5fc7bc1fbe5277f7d25a7798e9196742fcc77dc5d34445b11`,
matching the previously recorded archive. The archive contains
`rm614__08.71.mcusw` (29,271,181 bytes).

The MCUSW is an FPSX/B2 container. The named `MCUSW` region begins at flash
address `0x00420000`, contains 253 data blocks and reconstructs to
`0x00DBD200` bytes. The pinned upstream parser defines a named `0x5D` block
and contiguous `0x54` continuation blocks. The pinned `layout.rs` loads
the complete MCUSW region at virtual address `0x80130000`. Therefore guest
PC `0x8073A838` maps to offset `0x0060A838` within that MCUSW region.
A direct `PC - 0x80000000` offset is incorrect for this image.

### Disassembly and observed register contract

The bytes at that image offset decode as Thumb instructions. The surrounding
literal pool identifies the enclosing routine as
`SPIDRV: -Do_Read_Polling(bytesToRead=%d)`.

At guest PC `0x8073A838`:

- `0x8073A838: ldrb r3, [r2, #0x0c]` reads one byte from the status register.
- The literal loaded into `r2` is `0x088D0000`, so the actual address is
  `0x088D000C`.
- `0x8073A83A: lsls r3, r3, #30` moves status bit 2 into the sign bit.
- `0x8073A83C: bpl 0x8073A81E` repeats the poll while bit 2 is clear.

After bit 2 is observed set, this routine writes a 16-bit word to the data
register at offset `+0x08` (`strh r0, [r2, #0x08]`). It then polls the same
status byte again at `0x8073A852`, shifts it by 29 and branches back while
bit 3 is clear. Once bit 3 is set, it reads a 16-bit word from the data
register at `+0x08` (`ldrh r0, [r2, #0x08]`).

This establishes the firmware's minimum observed polling contract:

- status `0x088D000C` is read as an 8-bit value;
- status bit 2 gates a 16-bit write to `0x088D0008`;
- status bit 3 gates a 16-bit read from `0x088D0008`.

This is evidence of the driver's expected sequencing, not yet proof of the
hardware register's complete specification, reset value, or meanings of other
bits. Do not infer Broadcom target-side status bits from this Nokia SoC
host-controller register.

### Poll retry bound and interpretation of the old log

The same routine initializes a retry counter and compares it with
`0x8000`. On a failed status test it increments the counter and returns to
the read at `0x8073A838`; the compare/branch sequence bounds an individual
poll phase to `0x8000` attempts before its timeout path. The repeated
`icount` spacing of eight instructions in the earlier trace matches this
small polling loop.

Consequently, the old last-24-record window is evidence of repeated entry
into this specific TX-ready test. It does not prove that one invocation ran
continuously until the watchdog: the function has a finite retry path, and
higher-level retries or subsequent calls remain possible. The new rolling
ring capture is still needed to establish what happened after a poll phase
timed out and whether the next phase or request repeated.

### Engineering implications and outstanding validation

1. The next SPI model should expose the status conditions needed by this
   observed sequence: bit 2 before a halfword write to `+0x08`, and bit 3
   before a halfword read from `+0x08`. Do not implement speculative values
   for the other bits.
2. Continue disassembling the caller and the matching write-poll routine to
   determine startup/teardown, chip-select, transfer length, and error handling.
3. Do not implement a guessed status value or claim the physical peripheral has
   been validated from this static analysis alone.
4. Physical validation of the rolling trace is currently blocked: the user
   reports ADB displays `Waiting for device` and no log appears. The next
   diagnostic is the non-blocking `adb devices -l` listing; installation and
   fresh log capture remain pending until the phone is visible and authorized.

## 32. ADB connection restored for physical-device capture — 9 November 2026

The user ran `adb devices -l` from PowerShell and supplied:

```text
List of devices attached
6ea05c65               device product:curtana_global model:Redmi_Note_9S device:curtana transport_id:1
```

ADB now sees the Redmi Note 9S as an authorized `device`. The earlier
`Waiting for device` condition is no longer present at the device-discovery
layer. The next capture commands must explicitly target serial
`6ea05c65` with `adb -s 6ea05c65`, to avoid ambiguity.

Physical trace validation is still pending: confirm the package is installed,
start the logcat capture with the explicit serial, launch the app and boot/run
the RM-614 guest, then verify that `C3EmuPerf`, `C3EmuTrace` and
`C3EmuBus` lines are written to the capture file.

## 33. First physical rolling-ring capture confirms SPI TX-ready timeout — 9 November 2026

### Capture context

The user restored the USB ADB connection to the Redmi Note 9S:
`6ea05c65` (`curtana_global`; status `device`). The new APK ran and
emitted `C3EmuPerf`, `C3EmuTrace` and `C3EmuBus` records. VPN remained
enabled on the host so the user could access ChatGPT; USB ADB itself was
working normally. No evidence connects the host VPN to the guest SPI failure.

Guest boot began at 17:35:27.442 local log time. The guest later stopped at
17:36:11.722 with:

`OS fatal error: UKNOWN:0: BB H/W watchdog reset! (reset called from 0x80702931)`

### First SPI-host register sequence

The first-touch and rolling bus records expose these accesses in the selected
`0x088D0000..0x088D1FFF` range (values and PCs exactly as captured):

- `W1 0x088D0000 = 0x00` at PC `0x8081018C`;
- `W1 0x088D0004 = 0x00` at PC `0x80810198`;
- `W1 0x088D0014 = 0x00` at PC `0x808101E0`;
- `W1 0x088D0020 = 0x03` at PC `0x808101E8`;
- `W1 0x088D103C = 0x81` at PC `0x808101F8`;
- `W1 0x088D0010 = 0x02` at PC `0x80833D0C`;
- `W1 0x088D0024 = 0x03` at PC `0x80810AB4`;
- `R1 0x088D0018 = 0x00` at PC `0x8072BB2C`;
- `R1 0x088D000C = 0x00` at PC `0x8072BB4C`.

The first `C3EmuBus` snapshot at 17:36:08.017 reported
`eventCount=32801`, `bufferedCount=24`, `overwritten=32777`,
`otherEventCount=1121`. Its 24-row rolling tail contained 23 consecutive
`R1 0x088D000C = 0x00` reads from PC `0x8073A838`, each eight guest
instructions apart, followed by `W1 0x088D103C = 0x81` from PC
`0x8081054C`.

Subsequent snapshots at 17:36:10.020 and the final stop at 17:36:11.722
still reported `eventCount=32801` with the same SPI tail. Meanwhile
`otherEventCount` rose from 1121 to 1135 and then 1165, so the guest
continued producing non-SPI diagnostic events after its last access to this
SPI-host window. No additional SPI-window transaction was recorded before the
watchdog stop.

### Instruction-level interpretation

The reattached MCUSW image was reconstructed using the pinned upstream FPSX
layout: the named `MCUSW` region (flash address `0x00420000`, 253 blocks,
14,406,144 bytes) is loaded at guest address `0x80130000`. Therefore guest
PCs map into the region by subtracting `0x80130000`.

At `0x8073A838` the Thumb routine `SPIDRV: -Do_Read_Polling(bytesToRead=%d)`
does the following:

1. Read byte status `0x088D000C`.
2. Test bit 2; if clear, increment a 16-bit retry counter and repeat.
3. The counter limit is `0x8000`; when reached, the function returns
   error code `11` (`movs r0,#0x0b`).
4. When the TX-ready test succeeds, it writes a halfword to FIFO/data
   register `0x088D0008`.
5. It then waits for bit 3 at the same status register and reads a halfword
   from `0x088D0008`.

A separate RX FIFO routine at guest PC `0x8072BB4C` also polls bit 3 and
reads `0x088D0008` while bit 3 is set. Its adjacent code checks bit 7 of
`0x088D0018` and contains the diagnostic string
`SPIDRV: [ERIC]: RxFIFO Overrun!`.

At `0x8081054C`, the instruction is
`strb r1,[r0,#0x1c]`. The literal loaded into `r0` is
`0x088D1020`, and `r1=0x81`, so it writes
`0x088D103C = 0x81`. This store is in the `SPIDRV_IoRequest` path's
non-success handling branch (the same register is also written during
SPI initialization). The specific register meaning is not established yet.

The total `eventCount=32801` is numerically `0x8000 + 33`. This is
consistent with one complete 0x8000-attempt status timeout plus 33 other SPI
window transactions, but `eventCount` is aggregate across the whole MMIO
range and current output does not provide per-PC lifetime counts. Treat the
exact number of attempts as strongly consistent, not yet independently
proven by the trace. A future diagnostic can add a lifetime counter keyed by
PC/address/op without retaining every event.

### Root cause and next implementation boundary

The pinned emulator still has no SoC SPI-master device handling for this
`SOC_IO` range. An unclaimed, untouched register read falls through to sparse
backing and returns zero. The captured zero is therefore the emulator's
unimplemented-register fallback, not a measured Nokia hardware reset value.
The firmware's bit-2 TX-ready wait consequently never completes; no
halfword write to `0x088D0008` is reached in the retained tail. The observed
timeout and subsequent watchdog are consistent with the missing host
controller model.

Next work must be split into two explicit layers:

1. Implement a Nokia SoC SPI-host model at `0x088D0000..0x088D1FFF` using
   only the observed register access contract: TX FIFO readiness (bit 2),
   RX-data readiness (bit 3), halfword data access at `+0x08`, and the
   observed auxiliary status/control registers. Add deterministic unit tests
   for FIFO/status sequencing and avoid assigning undocumented semantics to
   other offsets.
2. Implement the board-connected Broadcom gSPI target as a separate device
   model, grounded in MCUSW initialization/download/IDB/NVS evidence and
   verified chip identity. Do not manufacture plausible target responses or
   claim Wi-Fi/Internet support from a host-controller stub.

This capture validates that the independent rolling ring works on a physical
Android device. It does not yet validate a modeled SPI controller or WLAN
target.

## 34. First stateful Nokia SPI-host model and explicit target boundary — 9 November 2026

### Source-level contract recovered

The RM-614 MCUSW 08.71 image was reconstructed from the user-supplied Phoenix
archive. The named MCUSW image is loaded by the pinned c3emu layout at
`0x80130000`; therefore guest PC mappings use that base.

The disassembly and physical trace establish the first host-controller subset:

- `0x088D0000` is a 16-bit configuration word. `SPI_Set_Clock` preserves its
  low byte and writes a computed divider into the high byte.
- `0x088D0004` is a byte control register modified by `SPI_Set_Master_Mode`.
  The routine clears bit 2, conditionally sets bit 2 from its argument, and
  always sets bit 1. The observed initialization call passes argument 1.
- `0x088D0008` is the 16-bit data/FIFO port.
- `0x088D000C` is a byte status register. Firmware tests bit 2 before the
  halfword data write and bit 3 before the halfword data read.
- `0x088D0010` receives byte value 2 in `SPI_Set_Clock`.
- `0x088D0018` bit 7 is tested as RX FIFO overrun.
- `0x088D0020` is written with 1 immediately after the overrun branch; init
  writes 3. The acknowledgement/clear role is strongly suggested, but other
  bits remain undocumented.
- `0x088D0024` receives byte value 3 during initialization.
- `0x088D103C` is derived from literal pointer `0x088D1020 + 0x1C`.
  Nearby strings name `IOCR2_REG`; firmware writes `0x81` and `0x80` on
  different paths. It is separate pin-mux/board-control space, not an SPI FIFO
  register. `IOCR0_REG` also appears in strings; its mapping is unresolved.

The exact evidence and confidence boundaries are written in
`docs/experiments/C3EMU_SPI_HOST_CONTRACT.md`.

### Isolated core fork and first implementation

A new branch `feature/rm614-spi-host-master-20261109` was created from the
then-current project head so the SPI work does not modify the prior development
branch directly.

The pinned upstream c3emu crate is being vendored at
`engine/c3emu-core` from
`androettop/c3emu@9b0d3ae2da326e109428f64d8760ea823ee1b648`. Its original
GPL-3.0-or-later licence and shell asset are preserved. The Android wrapper is
changed to depend on this local source copy, allowing the SoC bus dispatcher
to call a stateful device model.

The first `spi.rs` model is intentionally partial:

- exact access-width matching for the observed byte/halfword registers;
- bit 1 of control `+0x04` gates the initial TX-ready signal;
- bit 2 of status `+0x0C` reports a free immediate TX slot;
- bit 3 of status `+0x0C` is set only when a connected target supplies an RX
  halfword;
- data-port writes are sent to an explicit `SpiTarget` endpoint;
- no RX word is fabricated when no target is attached;
- the overrun status location and the observed acknowledgement write are
  represented, but FIFO depth/timing and most side effects remain unmodelled.

This does not yet model the Broadcom gSPI target, board power/IRQ/mux pins,
chip-select timing, clock waveform, or a cycle-accurate hardware FIFO. The
software RX queue is unbounded until its physical depth is established. No
Wi-Fi scan, association or Internet support is claimed.

### Tests and physical validation still pending

Four unit tests cover:
1. TX-ready is not exposed until the observed master-enable bit is set;
2. an unconnected target does not generate synthetic RX data;
3. an attached test target supplies an RX word and RX-ready clears when read;
4. the observed overrun status/acknowledgement path and access widths.

These tests and the Android native build are not yet CI-validated at the time
this section is written. The next gate is CI, then a fresh physical RM-614 run.
The expected first improvement is that the guest passes the former TX-ready
poll at `0x8073A838` and reaches the next target-dependent RX phase. If no
target is attached, an RX timeout is expected and must remain visible.

Next steps:
1. complete CI validation of the vendored core and SPI host model;
2. inspect the new trace after the guest passes the TX-ready wait;
3. decode the remaining `IOCR0/IOCR2`, CS, WLANENABLE, CLK_REQ and IRQ sequence;
4. only then implement a separately tested Broadcom gSPI target, after exact
   silicon/firmware identity is established.

## 35. First SPI-host integration CI and dedicated core-test gate — 9 November 2026

Push workflow #526 for commit `e9903d6c9fac1ea93034b16d9ebf542853c5a997`
completed successfully:

- Android catalogue and firmware tooling checks passed.
- Rust `c3emu-web` wrapper tests passed.
- Native JNI builds passed for `arm64-v8a`, `armeabi-v7a` and `x86_64`.
- Android unit tests passed.
- Debug APK assembly and artifact upload passed.

The first workflow did not execute unit tests located inside the vendored
`c3emu-core` path dependency: `cargo test` in the wrapper tests that package,
not the dependency's own `#[cfg(test)]` module. This was identified during
review before physical installation.

A separate CI step now runs:

```sh
cd engine/c3emu-core
cargo test --no-default-features
```

This explicitly executes the four SPI-host tests and the vendored core's
existing unit tests. The follow-up workflow must pass before an APK from this
branch is treated as eligible for physical-device installation.

The new host model remains partial and does not claim a Broadcom target or
Wi-Fi functionality. The next physical trace must confirm that TX-ready at
`0x088D000C` bit 2 advances past the former PC `0x8073A838` loop and reaches
the target-dependent RX-ready bit-3 wait.

## 36. SPI-host unit tests and full Android CI passed — 9 November 2026

Follow-up commit:
`18f299687f2f8eb9e5c189c0a58ba38d9d44aeb1`

Push workflow #527 completed successfully:
https://github.com/irman100/RetroMobileWorld/actions/runs/42656241983

Verified workflow results:

- Dedicated `cargo test --no-default-features` in `engine/c3emu-core`: **7 passed, 0 failed**, including all four `spi::tests`:
  - `attached_target_response_sets_rx_ready_until_data_word_is_read`;
  - `master_enable_exposes_tx_ready_without_fabricating_rx_data`;
  - `observed_overrun_clear_write_clears_rx_overrun_flag`;
  - `register_widths_are_not_silently_aliased`.
- Existing bounded MMIO diagnostic tests in `c3emu-web`: passed.
- Android JNI shared-library builds: passed for `arm64-v8a`, `armeabi-v7a` and `x86_64`.
- Android unit tests: passed.
- Debug APK assembly and artifact upload: passed.

Artifact:
- Workflow artifact ID: `13693026171`
- Artifact name: `retro-mobile-world-c3emu-debug`
- ZIP size: `8,034,724` bytes
- ZIP SHA-256: `8420b3b3a8cb28d1a57fb3181083fb105b3fdc15410f91a2918e915be4af26ac`
- Extracted APK size: `12,734,071` bytes
- APK SHA-256: `f0a84aee58cb5c66af82d5273346ae5ad95d1b5f893295d61758a1705f1a186c`

The APK contains the native c3emu library for all three tested ABIs. This confirms build/test correctness, **not** physical-device correctness. The artifact has not yet been installed or verified against the user's saved RM-614 session.

Next device gate: install this exact build over the existing application using `adb -s 6ea05c65 install -r` (do not uninstall or clear app data), boot the user's RM-614 firmware and capture C3EmuBus. Expected result is that the driver passes its bit-2 TX-ready loop at PC `0x8073A838` and reaches the bit-3 RX-ready wait. With no target attached, a later RX timeout is expected. It must not be masked by synthetic Broadcom replies.

