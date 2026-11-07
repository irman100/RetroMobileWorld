# Журнал продолжения: эмуляция Nokia 6300 RM-217

**Назначение:** единая точка восстановления контекста для следующих сессий. Перед продолжением прочитать этот файл, затем актуальные исходники и перечисленные трассы. Не повторять выполненные эксперименты и не принимать диагностические подстановки за модель реального оборудования.

**Обновлено:** 7 ноября 2026  
**Ветка:** `feature/j2me-library-bridge-20261104`  
**Последний полностью проверенный кодовый checkpoint:** `8d049de7d59017084f8e249f3ba81079b2cf0b61`  
**CI для него успешно:** [RM-217 Boot Probe #42379564262](https://github.com/irman100/RetroMobileWorld/actions/runs/42379564262) — Windows + Linux, trace summarizer tests, Rust tests, release builds и Windows trace-kit; [Android PR #42379564321](https://github.com/irman100/RetroMobileWorld/actions/runs/42379564321) и [Android push #42379559302](https://github.com/irman100/RetroMobileWorld/actions/runs/42379559302) — success, APK собраны.  
**Свежий Windows trace-kit:** ID `13577250544`, SHA-256 `a98694d3af681199f39c4d5ba1743cdc03ec5ab8c8e3cfc8097aa0f5ac5034a3`, размер 1,304,228 bytes; expires 2026-11-21 14:03:12 UTC.  
**Android debug APK:** PR artifact `13577136887` (SHA-256 `059d16e012eca85a35d644a14241e869b92e4c013987dd051c13a4b4961f1bc2`); push artifact `13577331086` (SHA-256 `e4a9bb91bf1b6b05d0e989a4c5c9ca213e04bf1c711bc278746981db328328d2`).  
**PR:** [#1](https://github.com/irman100/RetroMobileWorld/pull/1), открыт, не слит; ветка допускает merge.

Исторические проверки для предыдущих диагностических коммитов сохранены в соответствующих разделах ниже.

---

## 1. Краткий текущий итог

Получен воспроизводимый CPU-only переход **NOLO → Secure Service 0x06 → MCUSW** при диагностических подстановках готовности ASIC и кандидата JEDEC ID NOR. Это не полноценная эмуляция загрузки телефона.

Текущая точка остановки — BSE mapper:
- Статический проход создаёт identity mapping `0x00000000..0x00100000` (1 MiB) и помечает первый слот карты значением `0x3E`.
- Поздний проход вызывает тот же mapper для диапазона с базой `0x00000000` и длиной `0x01600000` (22 MiB).
- Mapper видит уже занятый первый слот и вызывает assertion по адресу **`0xF0103C0A`**.
- Затем MCUSW инициирует reset: запись `0x00000002` в `0x20032D14` из PC `0xF0103A48`.

На этом пути прежний assertion `0xF010431A` условно пропускается ветвлением в `0xF0104318`. Не путать старый и текущий адреса.

**Причина, по которой второй диапазон передаётся с базой 0, пока не установлена.** Значения карты не исправлялись вручную, assertion не подавлялся.

## 2. Что уже установлено

### 2.1 NOLO и таблица образов

- Архив содержит NOLO по адресу flash `0x00020300`; BB5 entry — `0x000206D0` (смещение `0x3D0`).
- NOLO берёт базу image TOC из глобальной переменной `0x080006A0`, начальное значение — `0x00000400`.
- Запись TOC имеет размер `0x20`; имя лежит со смещения `0x14`; конец списка помечен `0xFF` в первом байте поля имени.
- Если разместить TOC по `0x08006400`, NOLO его не сканирует. Размещение по `0x00000400` с относительными смещениями от этой базы позволяет найти MCUSW по адресу `0xF0100000`.
- NOLO вызывает Secure Service 0x06 через дескриптор около `0x00023480`. Диагностический trampoline восстанавливает сохранённые `r0-r3` из стека вызова NOLO и передаёт управление в проверенную BB5 entry MCUSW по `0xF01003D0`.
- Пути `--entry=mcusw` и `--entry=nolo --hle-secure-service6-launch` различны. На втором нельзя предполагать, что NOLO получил boot-tag list, который probe устанавливает для прямого запуска MCUSW.

### 2.2 ASIC readiness

Инструкция NOLO в районе `0x00020DF8` делает загрузку статуса, `LSLS #31`, затем условный переход по Z. Это тестирует **бит 0 исходного значения**, а не бит 31. Предыдущая трактовка бита 31 была ошибочной и исправлена.

Чтобы пройти ранние циклы ожидания в CPU-only сравнительном прогоне, используются диагностические overrides:

```text
--ready-bit=0x20032D80:0
--ready-bit=0x20032D20:0
--ready-bit=0x20032D40:0
```

Эти overrides не моделируют реальный reset/clock/status controller. Их нельзя включать в обычную RM-217 machine model без подтверждения поведения ASIC.

### 2.3 NOR и SDRAM

По RM-217/RM-222 service manual и board schematic:
- NOR: 256 Mbit = 32 MiB = `0x02000000`.
- SDRAM: 128 Mbit = 16 MiB = `0x01000000`.
- На схеме combo-memory обозначен компонент **K5G5629ATB-SF75**.
- Прошивка сама формирует SDRAM descriptor type `0x04`, size `0x01000000`, base `0x10000000`; это согласуется с окном `0x10000000..0x11000000`.

Вариант NOR ID `0x00EC:0x2208` — диагностическая гипотеза Samsung-family, поддержанная родственными сервисными логами, но точный ID для K5G5629ATB-SF75 пока не подтверждён отдельным datasheet или измерением чипа. В коде это opt-in `--nor-id=0x00EC:0x2208`, а не постоянная характеристика оборудования.

NOLO устанавливает временный Thumb NOR-ID helper по адресу около `0x08000870`; трасса показывает командную последовательность autoselect/reset и чтения manufacturer/device/sector protection. MEM_READ hook backend вызывается после текущего чтения, поэтому writeback из callback влияет на последующие чтения, а не на то, которое уже произошло. Это ограничение текущей диагностической модели.

Пробовались разные реализации:
- MMIO NOR не подходит для исполнения NOLO в выбранном ARM926 interpreter: его `fetch_pa()` разрешает fetch из RAM, а MMIO fetch завершает работу как `FETCH_UNMAPPED`.
- Поэтому в probe низкое окно остаётся исполняемой RAM. Кандидат ID обрабатывается отдельными hooks.
- Probe собирает 32-MiB образ из физических FPSX payloads, незаполненные интервалы оставляет `0xFF`, загружает его в низкое окно и повторно накладывает временный NOLO TOC по `0x400`.
- Первый сектор NOR по адресу 0 в предоставленном FPSX не содержит payload; он читается как стёртая flash (`0xFF`). Полный staging не означает, что весь физический NOR dump восстановлен из firmware update package.
- Полный NOR staging не устранил текущий BSE mapping collision.

### 2.4 CPU backend и границы модели

Pinned dependency: c3emu revision `9b0d3ae2da326e109428f64d8760ea823ee1b648`. Probe использует встроенный ARM926EJ-S / ARMv5TE interpreter (feature `disasm`, без optional `unicorn`); это не означает, что точная ревизия core в RM-217 RAPGSM независимо подтверждена.

Встроенный backend реализует CP15 state, MMU section/page walks и TLB, но имеет ограничения по ARM exception entry, IRQ/FIQ timing, caches/write-buffer и cycle-accurate bus. Backend observer фиксирует исключение и останавливает CPU, а не изображает полноценный exception vector flow.

### 2.5 Память и неизвестные bus-доступы

- 16-MiB SDRAM отображается явно по `0x10000000..0x11000000`.
- Спекулятивный high RAM bank `0x80000000..0x84000000` удалён.
- Неизвестные memory/bus accesses по умолчанию больше не превращаются молча в новые writable zero-filled RAM pages; probe сообщает unmapped bus fault и останавливается.
- Старое поведение доступно только как явный экспериментальный fallback `--legacy-unmapped-as-ram`.
- В strict режиме ранний NOLO доступ `WRITE_UNMAPPED 0x20050004` из PC `0x00021772` обнаружен до Secure Service 0x06. Legacy fallback скрывает этот отсутствующий ASIC decoder, но не является доказательством, что адрес — RAM.

## 3. Текущая BSE region-map картина

### 3.1 Список дескрипторов

Непустой список создаётся в области `0x080037A0`. Важные записи из свежей трассы:

| Адрес | Слова / поля | Наблюдение |
|---|---|---|
| `0x08003820` | `type=0x10, field=0x00A00000, size=0x01600000, base=0` | Предшествующая 22-MiB запись; роль поля пока не доказана |
| `0x08003830` / descriptor pointer `0x0800382C` | `type=0x24, size=0x02000000, base=0, manufacturer=0x00EC` | NOR descriptor |
| `0x08003840` | device `0x2208` и последующие поля | Продолжение NOR descriptor |
| `0x08003850` | `type=0x04, size=0x01000000, base=0x10000000` | SDRAM descriptor |

Список также содержит типы `0x0F`, `0x0A`, `0x0D`, `0x10`, `0x15`, `0x07`, `0x04`, `0x09`, `0x05`, `0x11`, `0x12`, `0x24`. Ранее отсутствовавшая запись type 0 появилась после диагностического ответа NOR ID. Её наличие не означает, что физическая NOR-модель уже полна.

### 3.2 Два прохода mapper

- Статический проход стартует из кода около `0xF0104898`; таблица статических записей начинается около `0xF0105AEC`. Он вызывает общий mapper в `0xF0103ECE` для identity mapping `0x00000000..0x00100000`.
- Нижний mapper помечает первый слот карты у `0x10090000` значением `0x3E` (запись около `0xF0103C3E`).
- Последующий region/allocator pass идёт через код около `0xF01043A0`, затем вызывает тот же mapper `0xF0103ECE` (возвратный адрес `0xF01043A5`).
- При коллизии аргументы helper включают base `0x00000000`, length `0x01600000` (22 MiB), alias `0x00000000`. В стеке mapper наблюдались map root `0x10090000`, region-list pointer `0x080037A0` и связанные локальные значения.
- Нижний mapper читает первый слот в `0xF0103C04`, обнаруживает `0x3E` вместо нуля и вызывает assertion в `0xF0103C0A`.
- Затем reset-controller write: `0x20032D14 <- 0x00000002`, PC `0xF0103A48`.

**Пока неизвестно:** почему проход, связанный с descriptor `0x0800382C`, подготавливает повторное отображение нулевого диапазона на 22 MiB. Нельзя самовольно менять base на `0x00A00000` лишь потому, что соседний descriptor содержит это число в другом поле. Сначала нужно установить layout и смысл полей, а также преобразования, выполняемые allocator loop.

## 4. Эксперименты, которые не следует повторять вслепую

1. **Путь к архиву на Windows.** Ошибка `os error 5` возникла, потому что probe получил путь к каталогу `E:\RM-217`, а не к ZIP-файлу. `Test-Path` принимает также каталоги. Перед запуском нужно проверять `-PathType Leaf` и указывать полный путь, например `E:\RM-217.zip`.
2. **Ready bit 31.** Это была неверная интерпретация Thumb-инструкции. Правильный тестируемый бит — bit 0.
3. **MMIO NOR.** Не использовать для исполняемого NOLO с этим ARM926 backend: fetch из MMIO не поддерживается в `fetch_pa()`.
4. **Подмена значения в MEM_READ callback.** Callback срабатывает после чтения; запись в память меняет последующие чтения, но не текущее.
5. **NOR array поверх синтетического TOC.** Без повторного восстановления TOC область `0x400` перекрывалась бы staged flash data, и NOLO застревал бы при повторном поиске MCUSW. Текущий код повторно восстанавливает TOC.
6. **Полная загрузка 32 MiB NOR.** Не устранила BSE assertion; начало NOR из FPSX пустое/стёртое. Не объявлять это решением.
7. **Strict bus mode.** Полезен для обнаружения отсутствующего декодера `0x20050004`, но останавливается до полного NOLO launch. Legacy fallback годится только для A/B трассировки.

## 5. Следующая задача (не менять карту наугад)

Нужно проследить происхождение аргументов mapper на участке перед `0xF01043A0`.

Рекомендуемая точечная трасса:
- дизассемблировать Thumb код MCUSW вокруг `0xF0104330..0xF01043B0`, `0xF0103ECE..0xF0104010` и статический проход `0xF0104880..0xF0104960`;
- поставить watch hooks на `0xF010433A`, `0xF0104346`, `0xF010437C`, `0xF010438A`, `0xF010439E`, `0xF01043A0`, `0xF0103ECE`;
- при каждом значимом проходе записывать `r0-r7`, `sp/lr`, источник `r5/r6`, исходные 16–32 байта descriptor по адресу указателя, указатель region-list и stack locals `SP+0x30..0x50`;
- определить, откуда берутся ноль и `0x01600000`, и как они соотносятся с descriptor type `0x10` и NOR descriptor type `0x24`;
- сопоставить вызов region allocator со статической таблицей `0xF0105AEC`;
- только после этого формулировать гипотезу о правильном layout; не patch-ить map base/size и не обходить assertion.

Сейчас в коде уже есть hooks на `0xF0103ECE` и `0xF01043A0`, расширяющие карту/стековые дампы. Следующий trace должен добавлять именно предшествующие инструкции/итерации, а не ещё один общий дамп той же точки.

## 6. Воспроизведение

Ниже — диагностический прогон, который использовался для прохода до MCUSW/BSE. Путь в `$firmware` должен указывать на ZIP-файл, а не папку.

```powershell
$probe = Join-Path $env:USERPROFILE 'Downloads\rm217-boot-probe.exe'
$firmware = 'E:\RM-217.zip'
$log = Join-Path $env:USERPROFILE 'Desktop\rm217-bse-trace.txt'

if (-not (Test-Path -LiteralPath $probe -PathType Leaf)) {
    throw "Не найден probe: $probe"
}
if (-not (Test-Path -LiteralPath $firmware -PathType Leaf)) {
    throw "Укажи полный путь именно к RM-217.zip: $firmware"
}

& $probe $firmware --entry=nolo --instructions=1000000 --trace=96 `
    --ready-bit=0x20032D80:0 `
    --ready-bit=0x20032D20:0 `
    --ready-bit=0x20032D40:0 `
    --nor-id=0x00EC:0x2208 `
    --legacy-unmapped-as-ram `
    --hle-secure-service6-launch `
    --trace-bse-memory `
    --watch-pc=0xF0103ECE `
    --watch-pc=0xF01043A0 2>&1 |
    Tee-Object -FilePath $log

Write-Host "Трасса сохранена: $log"
```

Этот набор флагов — воспроизведение диагностического сценария, не recommended default machine configuration. Для строгого обнаружения отсутствующих ASIC/MMIO декодеров убрать `--legacy-unmapped-as-ram`, но тогда NOLO остановится на раннем unmapped access `0x20050004`.

## 7. Где лежат рабочие материалы

Локальные файлы исследовательской сессии (не добавлять их в Git без отдельной необходимости):
- `/mnt/data/RM-217.zip` — пользовательский firmware archive.
- `/mnt/data/rm217-live-descriptor-trace.txt` — большой trace активного BSE map collision.
- `/mnt/data/rm217-bse-collision-callers.txt` — caller trace.
- `/mnt/data/rm217-map-allocator-context.txt` — allocator context.
- `/mnt/data/rm217-assertion-site-trace.txt` — assertion-site trace.
- `/mnt/data/rm217-full-nor-trace-bit0.txt` — trace полного NOR / ready-bit сценария.
- `/mnt/data/rm217-disasm-caller.txt`, `/mnt/data/rm217-disasm-mapper.txt`, `/mnt/data/rm217-disasm-static-map.txt` — дизассемблированные фрагменты MCUSW.

Сами firmware, пользовательские дампы, извлечённые проприетарные payloads и бинарные артефакты не должны попадать в Git/публичные CI artifacts. В репозитории хранятся probe, тесты и описания методики.

## 8. Правила ведения этого журнала

После каждого существенного диагностического шага добавлять:
- дату и точный commit SHA;
- бинарный artifact / CI run, если он использовался;
- команду и диагностические флаги;
- наблюдения с точными адресами/значениями;
- отдельные подпункты «Подтверждено», «Гипотеза», «Не установлено»;
- результат тестов/CI;
- следующий конкретный проверяемый шаг.

Не переписывать историю так, чтобы неудачные гипотезы выглядели подтверждёнными. Исправления предыдущих выводов явно отмечать.

---

## 9. Type-8 occupied-range scan (2026-11-06, commit `f8bb0581f16056d71cd795ed329111296e690d5c`)

### 9.1 Что добавлено в трассировку

В `engine/rm217-boot-probe/src/main.rs` добавлены наблюдатели на:
- `0xF010433A` — начальная загрузка полей очередного descriptor;
- `0xF0104346` — обработка найденного type-8 occupied record;
- `0xF010437C` — поиск очередного occurrence типа `0x08`;
- `0xF010438A` — состояние интервала после прохода type-8;
- `0xF010439E` — аргументы, подаваемые в общий mapper;
- снимок descriptor и stack locals `SP+0x30..0x4C`.

Инструментация наблюдательная: она не меняет guest registers, records, interval bounds или таблицу карты.

Рабочая трасса, полученная этим кодом: `/mnt/data/rm217-type8-range-trace.txt`. Она локальна и не должна добавляться в Git.

### 9.2 Подтверждено трассой и дизассемблированием

В descriptor list по `0x080037A0`:
- NOR descriptor начинается с `0x0800382C`: type `0x24`, capacity `0x02000000`, base `0x00000000`.
- Единственный найденный type-8 record начинается с `0x0800381C`; его поля:
  - `record+0x08 = 0x00A00000`;
  - `record+0x0C = 0x01600000`.

Thumb-код allocator использует `record+0x0C` как начало occupied range и `record+0x08` как размер: сравнивает start с текущей base, а затем вычисляет `base + size`. Следовательно, в этой структуре запись type `0x08` описывает занятый диапазон:

`[0x01600000, 0x02000000)`

Это последние 10 MiB 32-MiB NOR. Оставшийся префикс NOR для allocator — `[0x00000000, 0x01600000)`, размер `0x01600000` (22 MiB).

В call frame перед общим mapper (`0xF0103ECE`) трасса фиксирует:
- `SP+0x30 = 0x00000000` — base;
- `SP+0x34 = 0x01600000` — length;
- `SP+0x44 = 0x080037A0` — source descriptor list;
- `SP+0x48 = 0x10090000` — map root.

Значит, нулевая база не появляется из случайной порчи NOR ID или из-за пустого полного NOR staging. Она является результатом текущего прохода allocator по NOR descriptor и единственному type-8 occupied range.

В другом, более раннем проходе статическая таблица MCUSW уже создаёт mapping `[0x00000000, 0x00100000)` (1 MiB). Поэтому следующий запрос allocator `[0, 22 MiB)` пересекается с уже занятой первой областью. Нижний mapper находит marker `0x3E` в корневой карте около `0x10090000` и вызывает assertion `0xF0103C0A`; затем MCUSW пишет reset value `0x2` в `0x20032D14` из PC `0xF0103A48`.

### 9.3 Выводы по уровням достоверности

**Подтверждено:**
- Type-8 запись задаёт занятый отрезок `0x01600000..0x02000000` при текущей интерпретации полей, прямо следующей из инструкций allocator.
- После вычитания этого occupied range остаётся кандидатный интервал `0..0x01600000`.
- В статическом проходе ранее уже размечен префикс `0..0x00100000`.
- Mapper падает при встрече занятого первого слота; reset boundary зафиксирован отдельно.

**Рабочая гипотеза:**
- Конфликт связан с тем, что статический 1-MiB mapping не представлен в списке type-8 occupied ranges, который allocator использует при разбиении NOR на свободные интервалы.
- Возможное объяснение — статическая область и NOR allocator обслуживают разные alias/address-space слои, но текущая трасса этого не доказывает.

**Не установлено:**
- Является ли отсутствие type-8 записи на `0..1 MiB` ошибкой входного hardware configuration, следствием поведения Secure Service 0x06/NOLO, особенностью aliasing или допустимым для железа отдельным mapping.
- Какой именно static-table record на `0xF0105AEC` создаёт marker `0x3E` и почему его диапазон не исключён из NOR candidate interval.
- Должен ли этот mapping быть объединён, исключён из свободного диапазона или представлен иным способом. Ничего из этого пока не патчить.

### 9.4 Следующий проверяемый шаг

Разобрать static memory table, используемую циклом около `0xF0104880..0xF0104918`:
1. Снять фактические 16-byte entries таблицы, на которую указывает `0xF0105AEC` (не полагаться только на literal address; проследить функцию преобразования указателя около `0xF0104976`).
2. Для каждого entry записать base/size/flags и соответствующий вызов `0xF0103ECE`.
3. Установить точный entry, создающий `[0, 1 MiB)` и marker `0x3E`.
4. Сопоставить его с type-8 list lookup: проверить, должен ли такой region быть включён в occupied list, или существует другой список/адресный alias.
5. Добавить тест/trace assertions только после понимания формата entry; не менять guest map и не обходить assertion.

Не повторять общий watch на `0xF0103ECE` без расширения предшествующих данных: нынешняя точка уже известна. Следующий trace должен быть сосредоточен на статическом 16-byte entry table и функции `0xF0104976`.

### 9.5 Проверка кода

Коммит `f8bb0581f16056d71cd795ed329111296e690d5c` добавил type-8 trace hooks. Для него:
- RM-217 Boot Probe #42247616703 — success (Windows/Linux unit tests and build);
- Android CI #42247616706 — success;
- Android CI #42247608766 — success.

Эти CI run проверяют build/tests, а не успешную загрузку телефона. Прогон по firmware завершился на BSE assertion/reset boundary, описанных выше.



---

## 10. Статическая таблица MCUSW: 14 записей и alias-индексация BSE (2026-11-06)

### 10.1 Новые данные статической таблицы

В последнем исследовательском прогоне впервые сняты все 14 записей статической таблицы MCUSW как исходные 16-байтовые записи, а не только декодированные аргументы mapper.

Подтверждённые из этого снимка примеры:

| Запись | Физическая база | Размер | Адрес отображения (alias) |
|---|---:|---:|---:|
| №0 | `0x00000000` | `0x00100000` | `0x00000000` |
| №11 | `0x00000000` | `0x00100000` | `0xFF100000` |

Обе записи относятся к одному физическому первому мегабайту, но задают разные виртуальные aliases. Поэтому совпадение физической базы не означает автоматическую коллизию BSE.

### 10.2 Уточнение модели занятости

Новое уточнение по реализации BSE: проверка занятости выполняется через виртуальный alias. Карта разбита на слоты размером 1 MiB и индексируется alias-адресом.

Следствия:

- Статические записи №0 и №11 занимают разные alias-слоты.
- Нельзя объявлять поздний NOR mapping конфликтующим только потому, что его физическая база равна нулю.
- Для доказательства коллизии нужно восстановить alias, который поздний NOR allocator фактически передаёт mapper, а затем сравнить соответствующий слот с таблицей.
- Marker `0x3E` и assertion `0xF0103C0A` доказывают обнаружение занятого слота, но без точного alias ещё не доказывают, какая именно статическая запись его заняла.

### 10.3 Поправка к прежней интерпретации stack dump

Не считать прежнее значение alias, прочитанное только через прямой `cpu.mem_read(SP + offset)`, окончательным доказательством guest virtual stack value.

В закреплённой зависимости c3emu `Uc::mem_read()` читает физическую память. CPU-инструкция `LDMIA` выполняет guest virtual access через MMU. Поэтому физический дамп SP и фактическое значение, загруженное гостевой инструкцией, могут не совпадать при включённой MMU.

Для разделения этих случаев добавлены:

- commit `802004983cabf1b80691cab78b8c0aa76c8b43e3`: чтение CP15 SCTLR/TTBR/DACR/FCSEIDR, регистров mapper и MEM_READ наблюдение гостевых stack reads;
- commit `588b77363c1fa2eff18c0c686b57c7b425946456`: code hooks переведены на точные адреса инструкций, чтобы не активировать общий range-trace path c3emu.

В новом прогоне основным источником alias перед вызовом общего mapper считать фактические регистры вызова (в первую очередь `r2`) и подтверждённые guest virtual reads, а не старые физические stack snapshots.

### 10.4 Следующий точечный эксперимент

Не менять базу, размер, alias и не удалять статические записи.

Для каждого вызова mapper на `0xF0103ECE` сопоставить:

1. caller/LR: статический путь (`0xF010489D`) или allocator path (`0xF01043A5`);
2. `r0` — физическая база, `r1` — длина, `r2` — alias;
3. `r5/r6`, descriptor pointer и его 16–32 исходных байта;
4. CP15 SCTLR/TTBR/DACR/FCSEIDR и соответствующие guest stack-read события;
5. alias-индекс слота `alias >> 20`, значение этого слота в map root `0x10090000` и instruction, которая прочитала marker.

Цель — ответить, какой alias назначается кандидату NOR `[0x00000000, 0x01600000)`, почему он выбирается и какой именно слот уже занят. Только после этого можно формулировать исправление.

Рабочая трасса с полными 14 raw records должна оставаться диагностическим артефактом вне Git вместе с firmware. В репозитории хранить методику, нейтральные тесты и выводы; не добавлять пользовательский ROM/FPSX payload.

---

## 11. Android install checkpoint и граница запуска ОС (2026-11-06)

### 11.1 Устанавливаемая сборка

Для commit `588b77363c1fa2eff18c0c686b57c7b425946456` успешно завершён Android CI:

- workflow: [Android CI #42264879265](https://github.com/irman100/RetroMobileWorld/actions/runs/42264879265);
- artifact: `retro-mobile-world-debug`, artifact ID `13527345538`;
- APK: `app-debug.apk`, 2,447,176 bytes;
- SHA-256: `49ae1c5d9a5cd1815cc34dedf048b9de3aa707c2d4cfd7429256b39c819a0fec`;
- package: `org.retromobile.world`;
- version: `0.3.0-rm217-firmware`, versionCode `3`;
- minSdk `26`, targetSdk `36`.

APK извлечён из CI artifact и ZIP-проверка APK прошла. Это debug-сборка; её можно установить для проверки intake UI. Успешная CI сборка не равна проверке на физическом телефоне.

### 11.2 Что можно проверить на телефоне сейчас

- запуск приложения и отображение корпуса RM-217;
- открытие системного picker;
- импорт пользовательского Phoenix ZIP;
- сохранение MCU/PPM/IMAGE локально;
- восстановление статуса после перезапуска;
- показ VPL CRC и предупреждения о несовпадении.

Известный набор RM-217 07.30 пока имеет CRC mismatch для PPM: ожидается `C585B71C`, фактический CRC в ранее исследованном архиве — `613D565F`. MCU и IMAGE совпадают. Импортер сохраняет структурно допустимый набор и показывает предупреждение; не называть его подтверждённым загрузочным комплектом.

### 11.3 Что ещё не является доступным

Android `MainActivity` сейчас реализует выбор и импорт файлов, а `PhoneSystemEngine` — только контракт без зарегистрированной реализации. Нативный RM-217 CPU/SoC backend, карта периферии, LCD controller и маршрутизация клавиатуры в эмулируемую машину ещё не подключены.

Desktop `rm217-boot-probe` — диагностический инструмент. Он доходит до BSE mapper и останавливается на assertion/reset boundary; это не Android OS emulator.

Следовательно, текущий APK позволяет проверить реальный импорт и диагностику прошивки на телефоне, но не может загрузить Series 40. Кнопку запуска или фиктивный экран загрузки не добавлять до появления реального machine backend.

### 11.4 Приоритетный маршрут к первому экрану прошивки

1. Установить текущий APK и проверить реальный импорт на телефоне; записать фактические файлы/CRC и UX ошибки.
2. Получить совпадающий PPM для выбранного VPL либо документировать другой корректный manifest; не скрывать mismatch.
3. Завершить alias-indexed BSE trace из раздела 10 и устранить первопричину assertion доказанным способом.
4. Продолжить desktop boot до первой устойчивой LCD frame, поэтапно моделируя только подтверждённые RM-217 hardware registers/devices.
5. Лишь после стабильного desktop frame подключить проверенный machine backend к Android `PhoneSystemEngine`, экрану и физическим key down/up.

**Критерий первого реального успеха:** оригинальный RM-217 код проходит BSE mapper и формирует устойчивый LCD frame в desktop probe. После этого тот же проверенный backend портируется в Android. Простое открытие ZIP, успешные unit tests или достижение reset boundary не считать загрузкой телефона.


---

## 12. Correct guest-virtual diagnostics for BSE/MMU (2026-11-06, commit 304b297868d10ba31b8bbf2405b2ce6ad848c9b8)

### 12.1 Причина изменения

В pinned c3emu Uc::mem_read() — physical-memory API. Несколько старых диагностических helper-ов принимали гостевые указатели (SP, descriptor pointers, MCUSW table pointers, BSE region roots), но читали их напрямую как физические адреса. После включения ARM926 MMU такой снимок нельзя считать содержимым guest virtual address.

В c3emu той же pinned revision уже есть публичные helpers c3emu::mmu::read_virt, virt_word и virt_to_phys. Использовать их для read-only guest diagnostics.

### 12.2 Что исправлено

В engine/rm217-boot-probe/src/main.rs:

- read_stack_word теперь использует c3emu::mmu::virt_word.
- dump_memory_window читает guest VA через c3emu::mmu::read_virt.
- Secure Service 0x06 diagnostic path читает descriptor, MCUSW prefix и сохранённые startup registers через MMU.
- Stack frames, указатели из HW config и descriptor windows также читаются через MMU.
- Каждая попытка mapper на 0xF0103ECE дополнительно выводит caller/LR, r0 physical base, r1 length, r2 BSE alias, r2 >> 20 как alias slot index, guest VA→PA translation для r2 и candidate map-root из SP+0x48.
- Фактические guest stack loads выводятся как VA, PA, размер, загруженное значение и PC.

Инструментирование остаётся read-only: оно не меняет guest registers, memory map, static records или результат проверки занятости. Assertion не отключён.

### 12.3 CI и артефакты

Для commit 304b297868d10ba31b8bbf2405b2ce6ad848c9b8:

- [Android CI #42268498333](https://github.com/irman100/RetroMobileWorld/actions/runs/42268498333) — success.
- [RM-217 Boot Probe #42268498270](https://github.com/irman100/RetroMobileWorld/actions/runs/42268498270) — success для Linux и Windows.

Полученные из этого run артефакты:

- Windows artifact rm217-boot-probe-windows, ID 13527962015; executable SHA-256 8660a3e13e22daa3380de05a28e789a3f227e5ade84fe161099dea7ee550bd7d.
- Linux artifact rm217-boot-probe-linux, ID 13528147489; executable SHA-256 15dac1dacc62ec0e47829f335a1e13d775cb3ab67536cc80f4be1d36c71509c5.

Linux executable проверен локальным запуском --help; Windows artifact распакован и подтверждён как x86-64 PE executable. Firmware boot trace на этом сеансе не запускался: исходный пользовательский Phoenix archive и локальные trace artifacts не доступны в рабочем контейнере.

### 12.4 Следующий запуск на исходном RM-217 archive

Использовать latest Windows probe artifact из run #42268498270. Путь к firmware должен быть полным путём именно к ZIP-файлу, не к каталогу.

Рекомендуемый PowerShell запуск: положить rm217-boot-probe.exe в Downloads, установить переменную firmware на реальный RM-217 ZIP (в старом журнале использовался E:RM-217.zip), задать лог Desktopm217-bse-alias-mmuv2.txt и проверить оба пути с Test-Path -PathType Leaf.

Параметры запуска:
- --entry=nolo --instructions=1000000 --trace=96
- --ready-bit=0x20032D80:0 --ready-bit=0x20032D20:0 --ready-bit=0x20032D40:0
- --nor-id=0x00EC:0x2208 --legacy-unmapped-as-ram --hle-secure-service6-launch --trace-bse-memory
- --watch-pc=0xF0103ECE
- --watch-pc=0xF0103C04 --watch-pc=0xF0103C0A
- --watch-pc=0xF010433A --watch-pc=0xF0104346 --watch-pc=0xF010437C
- --watch-pc=0xF010438A --watch-pc=0xF010439E --watch-pc=0xF01043A0

Перенаправить stderr/stdout в Tee-Object -FilePath $log, после выполнения сохранить $LASTEXITCODE и сам текстовый trace.

Флаги ready-bit, nor-id, legacy-unmapped-as-ram и hle-secure-service6-launch остаются диагностическими гипотезами/подстановками из предыдущего воспроизводимого пути, а не подтверждённой RM-217 machine model. Их сохранить для сравнения с прежней трассой; не делать вывод, что это реальная аппаратная конфигурация.

В новом trace проверить:

1. Для static mapper calls с caller 0xF010489D: raw 16-byte entry, r0/r1/r2, alias index и фактическое содержимое указателя после MMU translation.
2. Для allocator mapper call с caller 0xF01043A5: те же аргументы и descriptor bytes.
3. Совпадает ли r2 >> 20 позднего allocator с alias slots 0x000 или 0xFF1; не подменять BSE alias значением PA, полученным ARM926 MMU walk.
4. Какой именно guest virtual slot read в районе 0xF0103C04 вернул marker 0x3E; сохранить PC, адрес чтения и значение.
5. После установления slot formula сверить её с map-root и static record. До этого не менять map base/size, alias или raw table.

### 12.6 Повторяемый PowerShell runner

В репозиторий добавлен scripts/run_rm217_bse_alias_trace.ps1 (commit 52b42a86486f54437c251b3be22d7a5d4de4c3c5). Скрипт проверяет, что probe и firmware ZIP существуют как файлы, запускает согласованный набор diagnostic flags, сохраняет полный вывод в Desktop\rm217-bse-alias-mmuv2.txt и возвращает exit code probe.

Windows artifact для запуска нужно распаковать рядом со скриптом так, чтобы rm217-boot-probe.exe находился в той же папке. Путь к Phoenix ZIP можно передать первым аргументом; значение по умолчанию — E:\RM-217.zip.

### 12.5 Чего этот commit не решает

Этот commit повышает достоверность диагностики, но не устраняет BSE assertion и не загружает Series 40. Следующая обязательная точка — реальная трасса с пользовательским RM-217 archive. После определения alias collision можно исправлять только подтверждённую причину, затем продвигать desktop probe к reset/exception path, периферии и первому устойчивому LCD frame.


---

## 13. Direct BSE slot-table read trace (2026-11-06, commit 324719a3b387d596fb96dc5542c48df9795ffbfb)

### 13.1 Что добавлено

В дополнение к MMU-correct guest snapshots добавлен read-only MEM_READ hook для guest VA range 0x10090000..0x1009FFFF. Он выводит события только когда PC находится в lower mapper range 0xF0103B00..0xF0103C20.

Каждое событие печатает:

- guest VA обращения;
- physical PA после c3emu::mmu::virt_to_phys;
- размер чтения;
- фактически прочитанное значение;
- PC инструкции mapper.

Это должно связать marker 0x3E с точным чтением slot table. Hook не пишет в table и не меняет ответ CPU. Alias-index вычисление остаётся отдельным: r2 >> 20. Не предполагать stride массива заранее — сопоставить адрес фактического read с alias slot и raw static entries.

### 13.2 CI

Для commit 324719a3b387d596fb96dc5542c48df9795ffbfb:

- [RM-217 Boot Probe #42270244079](https://github.com/irman100/RetroMobileWorld/actions/runs/42270244079) — success, Windows и Linux tests/build.
- [Android CI #42270244078](https://github.com/irman100/RetroMobileWorld/actions/runs/42270244078) — success.

Свежий Windows artifact:

- ID 13528533888;
- файл rm217-boot-probe-windows.zip;
- executable SHA-256 c4c68cf07b825719c640ed3541c713854dd2bd925d44de32dac980c82db4d31f.

### 13.3 Следующий фактический checkpoint

Запустить scripts/run_rm217_bse_alias_trace.ps1 с этим Windows executable и исходным RM-217 Phoenix ZIP. Нужны события BSE slot-table-read вместе с BSE mapper args, static-map entry и guest-stack-read. Следующий анализ должен определить индекс/stride/слот из фактического машинного чтения, а не подбирать формулу по одному marker value.

До появления этой трассы никакие static entries, NOR descriptor, alias bases или occupied-range records не изменять.


---

## 14. Результат реального BSE/MMU trace: alias-0 collision подтверждён (2026-11-06)

### 14.1 Файл фактического прогона

Пользователь выполнил `scripts/run_rm217_bse_alias_trace.ps1` на своём исходном RM-217 Phoenix ZIP и передал доступную часть вывода (2864 строки). Runner сработал; это не ошибка PowerShell и не проблема размера команды. В конце файла:
- trace сохранён в `C:\Users\CNC Plasma\OneDrive\Desktop\rm217-bse-alias-mmuv2.txt`;
- host probe exit code = 0.

Exit code 0 означает, что сам host probe завершился без необработанной ошибки. Он **не означает**, что гостевая прошивка прошла BSE: в trace присутствует guest assertion path `Failed assertion @ 0x%08x`, после чтения occupied marker. Устойчивый LCD frame не получен.

### 14.2 Формула BSE slot table установлена по нескольким независимым alias

Map root = `0x10090000`. Фактические guest reads дают:

| BSE alias | `alias >> 20` | Адрес прочитанного 32-bit slot | Значение до записи |
|---:|---:|---:|---:|
| `0x00000000` | `0x000` | `0x10090000` | `0x00000000` |
| `0xFFB00000` | `0xFFB` | `0x10093FEC` | `0x00000000` |
| `0xFF100000` | `0xFF1` | `0x10093FC4` | `0x00000000` |
| `0xFF200000` | `0xFF2` | `0x10093FC8` | `0x00000000` |
| `0xE0001000` | `0xE00` | `0x10093800` | `0x00000000` |
| `0xC1600000` | `0xC16` | `0x10093058` | `0x00000000` |

Все адреса согласуются с точной формулой:

```text
slot_address = 0x10090000 + 4 * (alias >> 20)
```

Следовательно, таблица — 4096 четырёхбайтовых слотов, индексируемых старшими 12 бит alias после сдвига на 20. Это уже не предположение на основе одного совпадения: формула проверяется на alias 0xFFB, 0xFF1, 0xFF2, 0xE00 и 0xC16.

Во время этого вызова CP15 SCTLR = `0x0009117C`, bit M (bit 0) выключен. Поэтому показанные для этих обращений VA и PA совпадают; не трактовать `guest_va_to_pa` как доказательство активной MMU-переадресации. Доказательством служит фактический guest MEM_READ и совпадающий адрес slot table.

### 14.3 Точная последовательность повторного использования alias 0

**Первая статическая инициализация BSE:**

- общий mapper `0xF0103ECE`, caller/LR `0xF010489D`;
- `r0=0x00000000`, `r1=0x00100000`, `r2=0x00000000`;
- BSE alias index = `0`;
- lower mapper читает `0x10090000` и получает `0`;
- на `PC=0xF0103C3E` записывает в тот же адрес marker `0x3E`.

Это подтверждает, что самый ранний mapping-запрос с physical base 0, length 1 MiB и alias 0 занимает BSE slot 0.

**Поздний NOR allocator:**

- общий mapper `0xF0103ECE`, caller/LR `0xF01043A5`;
- `r0=0x00000000`, `r1=0x01600000`, `r2=0x00000000`;
- BSE alias index снова = `0`;
- lower mapper читает `VA=PA=0x10090000` и получает `0x3E`;
- следующая watch point `0xF0103C0A` видит `r0=0x3E`; после этого guest assertion formatter пишет строку `Failed assertion @ 0x%08x`.

Это прямое подтверждение: поздний allocator повторно использует тот же BSE alias slot 0, который был помечен ранней статической инициализацией. Причина текущего assertion — не простое совпадение физических адресов; это совпадение **BSE alias index 0**.

### 14.4 Исправление интерпретации static-table cursor (2026-11-07)

Новая трасса вместе с дизассемблированием `0xF0104866..0xF01048A6` опровергает прежний вывод, что `r3` на mapper hook указывает на следующую static-table запись. `r3` указывает на **четвёртое поле текущей 16-byte записи**, то есть на `record + 0x0C`.

Логика цикла:
- `r6` — начало таблицы после relocation helper, `0xF0105AEC`;
- `r5` — индекс записи, `ip = r5 << 4` — смещение записи в таблице;
- `0xF01048A2`: `LDR r0, [r6, ip]` — физическая база текущей записи;
- `0xF0104892`: `LDR r2, [r1, #8]` — alias;
- `0xF0104894`: `LDR r1, [r1, #4]` — размер;
- `0xF0104896`: `ADDS r3, #0xC` — указатель на четвёртое поле той же записи;
- `0xF0104898`: вызов mapper с `r0/r1/r2` от текущей записи и `r3=record+0x0C`.

Например, при `index=0`:
- table root / record = `0xF0105AEC`;
- mapper args: physical base `0`, size `0x00100000`, alias `0`;
- `r3=0xF0105AF8 = record+0x0C`;
- word по `0xF0105AF8` = `0x000000C4`, четвёртое поле текущей записи;
- следующая запись начинается только с `0xF0105AFC`, где лежит её base `0x20100000`.

Таким образом, mapping `(physical=0, size=1 MiB, alias=0)` — это **первая запись самой static table**, а не отдельный mapping вне таблицы. Прежняя формулировка о том, что raw cursor опережает mapper arguments на одну запись, была ошибочной и заменяется этим выводом. Семантика четвёртого word пока не подтверждена; в таблице ниже он называется raw `+0x0C`, не приписывая ему смысл.

| Index | Record | Physical base | Size | BSE alias | Raw `+0x0C` |
|---:|---:|---:|---:|---:|---:|
| 0 | `0xF0105AEC` | `0x00000000` | `0x00100000` | `0x00000000` | `0x000000C4` |
| 1 | `0xF0105AFC` | `0x20100000` | `0x00100000` | `0xFFB00000` | `0x00000185` |
| 2 | `0xF0105B0C` | `0x40000000` | `0x00001000` | `0xFFC00000` | `0x00000005` |
| 3 | `0xF0105B1C` | `0x20010000` | `0x00001000` | `0xFFD10000` | `0x00000005` |
| 4 | `0xF0105B2C` | `0x20020000` | `0x00007000` | `0xFFD20000` | `0x00000405` |
| 5 | `0xF0105B3C` | `0x20030000` | `0x00007000` | `0xFFD30000` | `0x00000405` |
| 6 | `0xF0105B4C` | `0x20040000` | `0x00003000` | `0xFFD40000` | `0x00000405` |
| 7 | `0xF0105B5C` | `0x20040000` | `0x00003000` | `0xFFD40000` | `0x00000405` |
| 8 | `0xF0105B6C` | `0x20050000` | `0x00003000` | `0xFFD50000` | `0x00000405` |
| 9 | `0xF0105B7C` | `0x1C000000` | `0x00001000` | `0xFFE00000` | `0x00000405` |
| 10 | `0xF0105B8C` | `0xFFFF0400` | `0x0000FC00` | `0xFFFF0400` | `0x00000044` |
| 11 | `0xF0105B9C` | `0x00000000` | `0x00100000` | `0xFF100000` | `0x0000040D` |
| 12 | `0xF0105BAC` | `0x04000000` | `0x00100000` | `0xFF200000` | `0x0000040D` |
| 13 | `0xF0105BBC` | `0x00000001` | `0x00100000` | `0xE0001000` | `0x00000105` |

По trace после четвёртого поля записи №13, по адресу `0xF0105BCC`, следует sentinel `0xFFFFFFFF`. Наблюдаемая таблица содержит 14 mapper records.

Дополнительное наблюдение: records №6 и №7 идентичны по первым трём полям и raw `+0x0C`. Это нужно сохранить как факт, но не объявлять ошибкой: смысл записи и поведение повторной регистрации ещё требуют отдельного изучения.

Главное следствие для BSE: первая 1-MiB identity mapping действительно задана явной строкой static table. Поздний NOR allocator пытается использовать тот же физический base и alias для 22-MiB диапазона. Следующий вопрос — правило согласования этой static mapping с NOR free-range allocator, а не происхождение отдельного prelude за пределами таблицы.

### 14.5 Как это связано с type-8 свободным интервалом

Поздний allocator получает:
- текущий base `0x00000000`;
- length `0x01600000`;
- source descriptor `0x0800382C`;
- source list `0x080037A0`;
- map root `0x10090000`.

В том же descriptor cluster type-8 record у `0x0800381C` содержит поля, которые по ранее разобранным инструкциям allocator задают occupied interval `[0x01600000, 0x02000000)`. После его вычитания allocator считает `[0, 0x01600000)` свободным и назначает BSE alias 0, то есть alias равный началу candidate interval.

Таким образом, обнаружено рассогласование двух структур:
- static BSE slot table уже содержит занятую alias-0 mapping-запись на 1 MiB;
- type-8 allocator range list не исключает этот префикс из своего свободного candidate interval.

Это формулировка наблюдаемого конфликта, но пока не доказательство, какой из двух входов должен быть изменён на реальном RM-217: список occupied ranges, правила построения prelude mapping или политика назначения alias.

### 14.6 Следующий эксперимент — источник prelude mapping, а не ещё один общий dump

Следующий точечный шаг:

1. Разобрать caller loop `0xF0104880..0xF0104918` и инструкции непосредственно перед первым вызовом `0xF0103ECE`.
2. Проследить, откуда первый вызов получает `r0=0`, `r1=0x00100000`, `r2=0`, и почему этот вызов выполняется до raw descriptor с `0x20100000/0xFFB00000`.
3. Отдельно проследить, как allocator в `0xF010433A..0xF01043A5` выводит `r2=0` из свободного интервала `base=0`, `length=0x01600000`.
4. Проверить исходный формат/назначение type-8 и других типов в живой descriptor list, не только один type-8 item.
5. Сопоставить prelude mapping с boot tags/NOLO HW configuration. Определить, должна ли эта область быть зарезервирована в free-range list, использовать другой alias или поддерживать совместное/расширяемое отображение.

Не удалять prelude mapping, не смещать NOR base, не менять `r2`, type-8 record или assertion. До доказательства семантики это всё остаётся потенциальной подменой memory map, а не исправлением.

### 14.7 Текущий результат загрузки

Подтверждено:
- firmware доходит до BSE static map initialization;
- ранний mapping alias 0 занимает slot 0;
- поздний NOR allocator запрашивает тот же slot 0;
- firmware доходит до assertion path при чтении marker `0x3E`.

Не подтверждено:
- успешное прохождение BSE;
- успешный reset/next-stage handoff после BSE;
- стабильный LCD frame;
- запуск Series 40.

Поэтому этот прогон — важный воспроизводимый breakthrough в причине BSE assertion, но ещё не запуск ОС.

---

## 15. Type-8 occupied tail, предшествующие NOR mappings и источник alias (2026-11-06, commit `25b59d4f2d9925710ad821b3b2529c74ccbce17e`)

### 15.1 Повторный анализ уже полученной трассы

Этот этап основан на ранее переданном пользовательском trace `rm217-bse-alias-mmuv2.txt` (2864 строки). Новый запуск прошивки на телефоне в этом этапе не выполнялся. Исходный trace не добавлялся в GitHub: в репозитории сохраняются только код диагностик, тестовые fixtures и числовые наблюдения.

Подтверждённые mapper-вызовы из того же descriptor walk:

| Caller / LR | Physical base | Length | BSE alias | Alias slot |
|---|---:|---:|---:|---:|
| `0xF01041CF` | `0x01600000` | `0x00A00000` | `0xC1600000` | `0xC16` |
| `0xF01042E1` | `0x00000000` | `0x0000B000` | `0xC0000000` | `0xC00` |
| `0xF01043A5` | `0x00000000` | `0x01600000` | `0x00000000` | `0x000` |

Первый из этих вызовов отображает физический интервал `[0x01600000, 0x02000000)` — те же границы, которые задаёт Type-8 record. Для него используется отдельный alias `0xC1600000`; trace фиксирует заполнение соответствующих слотов начиная с `0xC16`. Второй вызов отображает небольшой нижний участок `[0, 0xB000)` через alias `0xC0000000`.

Поздний вызов `0xF01043A5` отображает свободный префикс `[0, 0x01600000)` через alias `0`. Именно он повторно обращается к BSE slot 0, ранее занятому static prelude mapping с alias `0`, и получает marker `0x3E`.

Это добавляет важный контекст к разделу 14: перед конфликтующим вызовом firmware уже создаёт отдельные BSE mappings для NOR-tail и небольшого low window. Совпадение physical range первого вызова с Type-8 occupied interval подтверждено значениями trace; точная связь source descriptor с caller `0xF01041CF` пока не доказана. Нельзя автоматически считать `0xC1600000` «правильным alias» для позднего свободного префикса: это другой физический диапазон и другой вызов.

### 15.2 Восстановленная Thumb-логика allocator

Из little-endian code words в старом trace восстановлен Thumb-код окна `0xF010432A..0xF010437A` и декодирован как ARM Thumb. Существенная последовательность:

```text
0xF010432A  BL    0xF0104976
0xF010432E  LDR   r4, [pc, #0xF4]
0xF0104330  SUBS  r0, r4, r0
0xF0104332  STR   r0, [sp, #0x38]
0xF0104334  STR   r0, [sp, #0x1C]
0xF0104336  MOVS  r0, #0
0xF0104338  B     0xF010448A
```

Thumb literal load по адресу `0xF010432E` с учётом `PC+4` и word alignment ссылается на literal address `0xF0104424`. Важный установленный факт: значение, которое затем передаётся общему BSE mapper как `r2`, инициализируется вычислением `r4 - r0` и сохраняется в `SP+0x38`. Вызов `0xF0104976` предшествует этому вычислению; вероятно, именно он возвращает один из операндов, но значение его результата в старой трассе не зафиксировано.

Отдельный участок Type-8 scan в том же code window:
- `0xF0104346`: `LDR r1, [r0, #0xC]` — загрузка start из записи.
- `0xF0104348`: загрузка текущего free base из `SP+0x30`; далее сравнение.
- При равенстве start и current base код добавляет descriptor size из `+0x08` к base и вычитает size из оставшейся длины.
- Если start находится строго внутри текущего free interval, код сокращает оставшуюся длину до `start - current_base`.

Для наблюдаемой записи `0x0800381C`:
- `record+0x08 = 0x00A00000` (size);
- `record+0x0C = 0x01600000` (start);
- occupied interval = `[0x01600000, 0x02000000)`;
- после scan = `base=0`, `length=0x01600000`;
- перед mapper `SP+0x38=0`, поэтому фактические args позднего вызова включают `r2=0`.

Это подтверждает работу free-range subtraction и место, откуда берётся mapper alias input. **Не установлено**, почему helper/literal дают нулевой результат для этой конкретной конфигурации. Также не установлено, должен ли статический alias-0 prelude mapping исключаться из free-range list или должен ли поздний allocator выбирать другой alias. Map firmware не менялся.

### 15.3 Что добавлено в код и тесты

В `scripts/summarize_rm217_bse_trace.py`:
- выделены ключевые mapper calls: static prelude, static alias `FF1`, previous high-range mapping `C16`, previous low window `C00`, late NOR allocator;
- добавлен разбор pointer Type-8 record, raw descriptor bytes и allocator intervals;
- вывод `record+0x08/+0x0C`, computed end и интервалов после scan / перед mapper;
- slot reads/writes объединяются по guest slot, чтобы краткий отчёт не повторял длинный trace.

В `scripts/tests/test_summarize_rm217_bse_trace.py` добавлен synthetic fixture с обоими предыдущими mapper calls, Type-8 raw fields и интервалами. Проверяется, что summary сохраняет ключевые события, считает все 45 synthetic mapper rows и не печатает посторонние mapper details. Тестовый fixture — проверка parser, не новая firmware trace.

После CI parser был дополнительно запущен локально на ранее переданном trace `Вставленный текст.txt` (2864 строки). Реальный старый trace дал summary на 58 строк: разобраны 19 mapper calls, 16 BSE slot reads и 37 BSE slot writes. Summary распознал вызовы `LR=0xF01041CF` (`C16`), `LR=0xF01042E1` (`C00`), поздний `LR=0xF01043A5` (`alias 0`), Type-8 record `0x0800381C` и интервал `[0, 0x01600000)`. Он также подтвердил signature marker: slot 0 был пуст, затем записан `0x3E`, затем прочитан как `0x3E`; assertion formatter присутствует. Это проверка инструмента на существующей трассе, не новый запуск firmware.

В `scripts/run_rm217_bse_alias_trace.ps1` добавлены exact-PC watchpoints:
- `0xF010432A` — состояние до вызова helper `0xF0104976`;
- `0xF0104330` — оба операнда непосредственно перед `SUBS r0, r4, r0`;
- `0xF0104332` — вычисленный alias value перед записью в `SP+0x38`;
- `0xF0104874`, `0xF0104880`, `0xF0104898` — регистры вокруг начала static-map loop и перед mapper sequence.

Эти точки должны закрыть два пробела: откуда helper получает входное значение и как static-map prelude формируется до первого вызова общего mapper.

### 15.4 Аппаратная документация: подтверждено и остаётся открытым

Nokia Level 3/4 service manual для RM-217/RM-222 указывает BB5.0 с RAPGSM и combo memory `128 Mbit SDRAM + 256 Mbit NOR flash` (D3000). [Service manual PDF](https://usermanual.wiki/Document/nokia6300rm2176300brm222servicemanual34v10.1237121143.pdf).

Схема платы называет D3000 компонентом `K5G5629ATB-SF75` и маркирует combo как `256M NOR + 128M DDR DRAM`. [Service schematic PDF](https://usermanual.wiki/Document/nokia6300rm2176300brm222serviceschematicsv20.673614552.pdf).

Сохраняется терминологическое различие источников: manual пишет SDRAM, схема — DDR DRAM. Точный Samsung datasheet для суффикса `K5G5629ATB-SF75`, его bus timings и доказательство идентичного состава кристалла из доступных материалов здесь не установлены. Нельзя выводить JEDEC ID `0x00EC:0x2208` из одного номера компонента. NOR ID и ASIC ready-bit flags в probe по-прежнему диагностические overrides, а не подтверждённая аппаратная модель.

### 15.5 CI, trace kit и следующий checkpoint

Для HEAD `25b59d4f2d9925710ad821b3b2529c74ccbce17e`:
- [RM-217 Boot Probe #42284063828](https://github.com/irman100/RetroMobileWorld/actions/runs/42284063828) — success, Windows и Linux tests/build.
- [Android CI #42284063979](https://github.com/irman100/RetroMobileWorld/actions/runs/42284063979) — success.
- [Android CI #42284059351](https://github.com/irman100/RetroMobileWorld/actions/runs/42284059351) — success.

Опубликован свежий Windows BSE trace kit:
- artifact `rm217-windows-bse-trace-kit`, ID `13537156195`;
- SHA-256 `6da045af2e496956088251af63ca15890bf5c8bfbefc45af4442f2328f21aa3a`;
- комплект содержит probe executable, PowerShell runner, summarizer и README;
- архив был скачан и распакован для проверки: ровно 4 плоских файла, без вложенного ZIP;
- локальный SHA-256 архива совпал с GitHub digest: `6da045af2e496956088251af63ca15890bf5c8bfbefc45af4442f2328f21aa3a`.

Следующий запуск должен использовать комплект из указанного CI run и исходный RM-217 Phoenix ZIP. Пример PowerShell:

```powershell
.\run_rm217_bse_alias_trace.ps1 'E:\RM-217.zip'
```

Runner сохраняет полный trace `Desktop\rm217-bse-alias-mmuv2.txt` и краткую сводку `Desktop\rm217-bse-alias-mmuv2.summary.txt`. Для первичного анализа достаточно прислать summary; полный trace сохранить на случай необходимости проверить конкретные PC windows.

В новом trace проверить:
1. `PC=0xF010432A`: входные регистры перед helper `0xF0104976`.
2. `PC=0xF0104330`: `r0` после helper, literal-derived `r4`, вычисляемое `r4-r0`.
3. `PC=0xF0104332`: `r0` и stack destination `SP+0x38`.
4. `PC=0xF0104874/4880/4898`: источник static prelude mapping с `(base=0, size=1 MiB, alias=0)` и соответствие cursor/raw table.
5. Поздний вызов `0xF01043A5`: проверить, что `SP+0x38` без дополнительного преобразования становится `r2=0`.

**Не менять** static table, Type-8 record, NOR base, alias calculation или assertion до этого trace. Не считать host probe exit code 0 прохождением guest BSE. На текущем checkpoint firmware всё ещё останавливается на BSE assertion; stable LCD frame и запуск Series 40 не подтверждены.

---

## 16. PPM CRC-32 discrepancy: ZIP entry is self-consistent, selected VPL is not (2026-11-07)

### 16.1 Exact archived component evidence

The previously inspected user-supplied archive is product code `0537625`, VPL `RM217_0537625_07.30_018.vpl`, software version `07.30`, variant `RM-217 6300 EURO-C TR Turkey, Latin, black-silver`.

| Component | File size | ZIP central-directory CRC | Recalculated CRC-32 | Selected VPL CRC-32 | SHA-256 |
|---|---:|---:|---:|---:|---|
| MCU | 15,204,164 | `01C45DB7` | `01C45DB7` | `01C45DB7` | `80172266f517180ace7a0065d0167619c174ec3fc1aa181fdc9b9f6fc0c2e17d` |
| PPM | 4,525,730 | `613D565F` | `613D565F` | `C585B71C` | `166ee371be01e8fa070a60202bae085171a169e970e73e782e8e5730ac99276d` |
| IMAGE | 3,674,450 | `5008F91F` | `5008F91F` | `5008F91F` | `15f2a47216bd7deae8223e62d2579005edd9b0f26f244f469aa269c2f61b818b` |

The archive passes `zipfile.testzip()`. For PPM, the ZIP CRC and an independently recalculated `zlib.crc32(decompressed_component_bytes)` are both `613D565F`; they differ from the VPL's `C585B71C`. Therefore the bytes currently stored in this ZIP are self-consistent with the ZIP container, but are not the bytes expected by the selected VPL manifest. This is not evidence of accidental damage during ZIP reading/decompression. It remains possible that PPM was wrong/substituted/damaged before the ZIP was assembled, or that the manifest metadata is stale/incorrect.

All six VPL files in this ZIP reference the same PPM filename and expected checksum `C585B71C`; the ZIP has one matching PPM entry. The PPM FPSX header is readable and labels language-pack version `07.30`, but a matching version string and parseable structure do not prove exact package/variant identity.

### 16.2 Scope and relevance to the BSE failure

MCU CRC matches the ZIP entry and selected VPL; IMAGE does too. This makes accidental corruption of MCU bytes less likely, though CRC-32 is not an authenticity guarantee and no independently trusted SHA-256 baseline for this precise package has been obtained.

The present CPU/BSE probe loads and executes the MCU component. It does not consume or execute the PPM during the investigated NOLO/MCUSW/BSE initialization. Therefore the PPM mismatch does **not** explain the observed static-prelude alias-0 collision, the `0x3E` marker, or assertion at `0xF0103C0A`. It remains a separate integrity blocker before claiming a complete Series 40 boot, because PPM is a firmware component that may be used later for language/resource data.

### 16.3 Historical reports are only weak corroboration

Older user reports in Nokia repair forums mention `Corrupted Flash File` or PPM reinstall errors with RM-217 / software `07.30`, including `rm217__07.30.ppm_c`. These are anecdotal reports, not Nokia documentation, and are not verified to be the same product code, VPL or archive. They do not prove this archive's PPM is the same damaged file:

- [Unlockers archive: PPM_C 07.30 reported as a corrupted flash file](https://www.unlockers.ru/archive/index.php/t-15828.html)
- [Unlockers archive: RM-217 07.30 PPM reinstall reports](https://www.unlockers.ru/archive/index.php/t-37017.html)

### 16.4 Firmware inspector clarification and tests

`scripts/inspect_rm217_firmware.py` now reports two independent dimensions: `ZIP entry consistency` and `VPL component checksum`. For the PPM mismatch it explicitly reports that the ZIP entry is self-consistent but does not match the selected VPL, rather than presenting the generic word `MISMATCH` without context.

`scripts/tests/test_inspect_rm217_firmware.py` now tests four cases: all CRCs match; ZIP entry matches its own data while VPL differs; ZIP entry CRC differs while VPL matches; and the VPL checksum is missing. This protects the distinction between container integrity and manifest agreement.

For these CRC-inspector changes, commits are `81084b67260d8988282e4ee31348551e9a652e2a` and `7e0b7fb549c460d1b2395d7c0b485b49f8f03ab2`; follow-up explanation is in `88a97c15a35aca33cb1206fee95e34941c3f8321`. At the moment this section was written, the corresponding RM-217 Boot Probe and Android CI runs were still in progress. Do not mark those runs successful until their conclusions are checked.

### 16.5 Required PPM verification before complete-OS claim

1. Keep the matching MCU and IMAGE components unchanged; both currently pass the three-way CRC check.
2. Obtain an independently sourced PPM for this exact product code/VPL/variant, or an independent copy of the original package, and calculate SHA-256 and CRC-32 again.
3. A candidate PPM is accepted as matching this VPL only when its decompressed component CRC-32 equals `C585B71C`; also inspect FPSX structure and language/variant metadata.
4. If an independent copy of the exact same VPL/package still contains PPM CRC `613D565F`, investigate whether the VPL's `C585B71C` is stale or the package was published inconsistently. Do not silently rewrite the VPL checksum.
5. Do not conflate this PPM issue with the still-open BSE allocator problem. Continue the BSE investigation using the MCUSW; preserve the PPM warning as a separate boot-completeness gate.

**Current conclusion:** the ZIP container is structurally valid; the MCU and IMAGE match the selected VPL; the PPM component is internally consistent with the ZIP but mismatches the selected VPL. This proves package-level inconsistency for PPM, not that the entire firmware dump is corrupt.

---

## 17. Automated allocator alias derivation and refreshed trace checkpoint (2026-11-07)

### 17.1 What changed

The previous trace identified the allocator instruction sequence but did not capture the helper's returned value. The compact summarizer now reconstructs the arithmetic from the exact-PC register snapshots:

| PC | Checkpoint | Required observation |
|---|---|---|
| `0xF010432A` | immediately before `BL 0xF0104976` | helper input registers |
| `0xF0104976` | helper entry | entry register state |
| `0xF010432E` | returned from helper, before literal `LDR` | helper return value in `r0` |
| `0xF0104330` | after literal `LDR`, before `SUBS` | helper result in `r0`, literal in `r4` |
| `0xF0104332` | after `SUBS r0,r4,r0`, before `STR [sp,#0x38]` | computed alias in `r0` |

The summarizer calculates `(r4 - r0) & 0xFFFFFFFF`, compares it with post-instruction `r0`, checks that the helper return survives the intervening literal load, and compares the arithmetic result with the allocator's captured `SP+0x38` value immediately before the mapper call. It prints explicit MATCH/MISMATCH states and warnings. This is trace interpretation only; it does not modify guest registers, memory, mappings or assertion behaviour.

Two synthetic tests cover both a consistent register/stack flow and an intentionally inconsistent result. They test the diagnostic logic, not a new firmware execution.

### 17.2 CI and refreshed Windows trace kit

The code checkpoint is `558c35bb48e4cd2719146ce998f4d0668a23638c`.

- [RM-217 Boot Probe run 42366177923](https://github.com/irman100/RetroMobileWorld/actions/runs/42366177923) — success on Ubuntu and Windows. Both platforms passed the trace summarizer tests, Rust unit tests and release build. The Windows job also assembled and uploaded the trace kit.
- [Android CI pull-request run 42366177892](https://github.com/irman100/RetroMobileWorld/actions/runs/42366177892) — success, including Android unit tests, debug APK assembly and artifact upload.
- [Android CI push run 42366175996](https://github.com/irman100/RetroMobileWorld/actions/runs/42366175996) — success.
- Fresh Windows artifact: `rm217-windows-bse-trace-kit`, ID `13571579203`, SHA-256 `49b79d8234405d1ee57f9032eef7176a8fd5018799c0ff96d1e4217df3b91ec6`. It belongs to run 42366177923 and expires 2026-11-21 11:00:30 UTC.
- Android debug APK artifact: `retro-mobile-world-debug`, ID `13572130229`, SHA-256 `0dc3669aa719730bea53f5fefabb6e98418b5df05782e2c6ac203717291d363f`. This is the firmware-intake Android application build; it is not an APK with the original Series 40 OS running.

### 17.3 What this does not establish

No new run of the user's firmware archive was performed for this checkpoint. The source evidence for the alias-0 collision remains the previously supplied 2864-line trace. The new tests prove that the summarizer can validate the upcoming register arithmetic when those observations are present; they do not prove what the helper returns on the actual firmware run.

The BSE alias-0 collision and assertion at `0xF0103C0A` therefore remain unresolved. No stable LCD frame, successful MCUSW continuation, or Series 40 startup is confirmed. The PPM/VPL CRC discrepancy remains a separate package-integrity issue and is not part of this allocator calculation.

### 17.4 Next evidence checkpoint

Download the latest Windows trace kit from [run 42366626010](https://github.com/irman100/RetroMobileWorld/actions/runs/42366626010), artifact 13571708963 (SHA-256 ef99d059acab360556713f6b3693797510e53c30248a6acee704364680def20c), extract its flat files together, and run from PowerShell with the same user-supplied archive:

```powershell
.\run_rm217_bse_alias_trace.ps1 'E:\RM-217.zip'
```

Inspect the generated `rm217-bse-alias-mmuv2.summary.txt` first. The decisive values are the helper return at `0xF010432E`, `r4` and `r0` at `0xF0104330`, the post-`SUBS` `r0` at `0xF0104332`, and the captured `SP+0x38` before mapper. If the arithmetic and stack value both match but remain zero, the next investigation stays on the provenance of the zero result and the prelude/static-map construction around `0xF0104874`, `0xF0104880` and `0xF0104898`. Do not change Type-8 records, NOR base, alias policy or firmware bytes from the trace alone.


### 17.5 Current branch HEAD and newest artifact (2026-11-07)

The last fully tested runtime/tooling checkpoint is 75ae7edc0f37eef4b8f79c6504bc4d28da072014. Subsequent commits only refresh these reports and correct the PowerShell example; they do not change the tested runtime or trace-analyzer code from checkpoint 558c35bb48e4cd2719146ce998f4d0668a23638c.

- [RM-217 Boot Probe #42366626010](https://github.com/irman100/RetroMobileWorld/actions/runs/42366626010) — success on Windows and Linux, including summarizer tests, Rust tests, release builds and trace-kit upload.
- [Android CI #42366626025](https://github.com/irman100/RetroMobileWorld/actions/runs/42366626025) — success.
- [Android CI #42366622554](https://github.com/irman100/RetroMobileWorld/actions/runs/42366622554) — success.
- Latest Windows trace kit: artifact 13571708963, SHA-256 ef99d059acab360556713f6b3693797510e53c30248a6acee704364680def20c, 1,299,370 bytes, expires 2026-11-21 11:06:08 UTC. This newer artifact supersedes artifact 13571579203 above.

The next unresolved dependency is still a fresh run on the user's original local firmware ZIP. The runtime diagnosis must not advance from synthetic fixtures alone.
---

## 18. Новая фактическая трасса: физическая память, ARM MMU и BSE alias (2026-11-07)

### 18.1 Полученный пользовательский trace

Пользователь передал Desktop.zip с тремя файлами:
- rm217-bse-alias-mmuv2.txt — полный Windows PowerShell trace, 5 427 строк, 843 752 bytes;
- rm217-bse-alias-mmuv2.summary.txt — краткая сводка, 17 980 bytes;
- log.txt — хвост PowerShell-сессии, 18 798 bytes.

SHA-256 исходных файлов:
- Desktop.zip: f556881898cf0f8d7c44af9f38b034b7c06c640d88e2857d9673762fa83b22e8
- full trace: f4df7484c49294f8d87a898fc3d25fd6237f92de109bb825efb987eecce25ef6
- summary: 949bf98eaa3c1f7a91c3397814e09786628d4db720376331cefe36c83d5c1384
- PowerShell tail: 855c7bf7184d4e0cfb38ad7d2f8321b1061ec27a91e6c4814de12cd808022da9

Этот trace содержит 19 mapper calls, 16 BSE slot-table reads и 37 BSE slot-table writes. Полный trace и firmware archive остаются вне Git; здесь сохраняются только проверенные числа и методика.

### 18.2 Три разных адресных сущности

Предыдущее замечание в разделах 10 и 12 было правильным: физический API Uc::mem_read() нельзя смешивать с guest-virtual CPU loads, когда ARM MMU включена. Но новую трассу нужно интерпретировать отдельно, не переносить на неё старую оговорку автоматически.

На точке позднего mapper 0xF0103ECE получены:
- CP15 SCTLR = 0x0009117C; bit 0 (M) = 0;
- TTBR = 0x00000000;
- DACR = 0x00000000;
- FCSEIDR = 0x00000000.

Следовательно, ARM MMU address translation выключена именно на sampled late-mapper hook. BSE slot-table access для slot 0 записан VA=PA=0x10090000. Это означает, что различие CPU virtual vs physical stack reads, важное в старых MMU-enabled diagnostics, не объясняет повторное чтение slot 0 в этой трассе. Не следует обобщать этот M=0 на все стадии boot: он установлен только для данной точки.

Третий уровень — BSE alias. Это отдельный аргумент firmware mapper (r2), выбирающий слот собственной alias table по alias >> 20. Это не CPU virtual address и не результат CP15 VA-to-PA translation. Поле guest_va_to_pa в диагностике относится к отдельному CPU-адресному преобразованию.

### 18.3 Разные физические memory windows и текущая коллизия

В live descriptor cluster различимы два разных физических memory windows:
- NOR descriptor type 0x24: base 0x00000000, size 0x02000000 (32 MiB), manufacturer/device fields 0x00EC:0x2208 — кандидат ID остаётся диагностическим, не подтверждённым datasheet;
- SDRAM descriptor type 0x04: base 0x10000000, size 0x01000000 (16 MiB).

Таким образом, физическая NOR и SDRAM действительно разные виды памяти и имеют разные базовые адреса. Но пара, которая вызывает текущий assertion, не является NOR-vs-SDRAM collision: оба конфликтующих mapper requests используют physical base 0 и BSE alias 0.

| Mapper caller | Physical base (r0) | Length (r1) | BSE alias (r2) | BSE slot |
|---|---:|---:|---:|---:|
| Static prelude 0xF010489D | 0x00000000 | 0x00100000 | 0x00000000 | 0x000 |
| Late NOR allocator 0xF01043A5 | 0x00000000 | 0x01600000 | 0x00000000 | 0x000 |

Static prelude reads empty slot 0x10090000, then writes marker 0x3E. The later allocator reads the same slot and gets 0x3E. Different range lengths do not change the fact that both requests select the same BSE alias slot.

### 18.4 Откуда теперь доказанно берётся нулевой alias

Новая трасса закрывает прежний пробел о helper return:

1. 0xF0104328 loads r0 from PC-relative literal address 0xF0104424.
2. BL 0xF0104976 returns; at 0xF010432E, r0=0xF0100000.
3. LDR r4 at 0xF010432E references the same literal address 0xF0104424; at 0xF0104330, r4=0xF0100000.
4. SUBS r0,r4,r0 produces r0=0 at 0xF0104332.
5. The value is stored at SP+0x38; before the mapper that stack field remains zero, and caller 0xF01043A5 passes r2=0.

The helper at 0xF0104976 performs a PC-relative adjustment on its input. In this observed call it returns the input XIP base unchanged (0xF0100000). The caller's two operands therefore match, and the subtraction is correct. The current trace does not indicate register corruption or a failed stack store.

The Type-8 record at 0x0800381C still removes the occupied NOR tail [0x01600000, 0x02000000) from candidate interval [0, 0x02000000), leaving the allocator's free prefix [0, 0x01600000). That confirms the range subtraction observed earlier, but does not answer whether the static 1-MiB prelude must be reserved from this free prefix or whether the BSE mapper supports extending a same-origin mapping.

### 18.5 Что доказано и что остаётся открытым

Доказано:
- ARM MMU выключена на точке позднего mapper;
- slot-table read для slot 0 проходит по VA=PA=0x10090000;
- оба конфликтующих запроса используют physical base 0, BSE alias 0;
- нулевой alias получается из корректного вычитания двух одинаковых XIP-base operands;
- Type-8 occupied-range scan оставляет свободный физический префикс [0, 0x01600000).

Не доказано:
- что именно семантически представляет собой static prelude mapping первой 1 MiB;
- должен ли prelude mapping быть исключён из allocator free-range list;
- поддерживает ли firmware безопасное расширение уже занятого BSE slot для того же physical origin;
- какая из этих структур является источником intended reservation policy.

Не менять firmware bytes, NOR base, Type-8 data, alias policy или assertion без дополнительного доказательства.

### 18.6 Изменение диагностической сводки

В scripts/summarize_rm217_bse_trace.py добавлено отдельное пояснение CPU MMU vs BSE alias:
- парсинг SCTLR/TTBR/DACR/FCSEIDR;
- интерпретация SCTLR.M bit;
- показ фактических VA/PA для slot-0 reads;
- явное отделение mapper BSE alias (r2) от ARM VA-to-PA translation.

В scripts/tests/test_summarize_rm217_bse_trace.py добавлены synthetic cases для MMU disabled и MMU enabled. Они проверяют интерпретацию адресных пространств и не заменяют реальный firmware trace. CI для коммитов этой диагностической правки ещё необходимо проверить; до результата не считать её green.

### 18.7 Следующий точечный шаг

Разобрать 16-byte static-table record, который предшествует raw cursor 0xF0105AF8, и выяснить назначение prelude mapping (physical=0, size=1 MiB, BSE alias=0). Для этого можно добавить только read-only dump всей таблицы начиная с 0xF0105AEC, не меняя её contents. Затем сопоставить static entry с NOR descriptor и Type-8 reservation list.

---

## 19. Static table restored from the real trace; previous cursor interpretation corrected (2026-11-07)

The 5,427-line trace plus the disassembly at `0xF0104866..0xF01048A6` restores all 14 static mapping rows. This corrects section 14.4: `r3` is not a pointer one descriptor ahead. The loop computes `r3 = current_record + 0x0C`, the address of the current row's fourth word.

The loop uses `r6` as the relocated table base `0xF0105AEC` and `ip = index << 4`. It loads base from `[r6 + ip]` into `r0`, size from `[record + 4]` into `r1`, BSE alias from `[record + 8]` into `r2`, and passes `record + 0x0C` in `r3`.

At index 0, the mapper receives `(physical=0, size=1 MiB, alias=0)` from row `0xF0105AEC`; `r3=0xF0105AF8` points to its fourth word `0xC4`. The next row starts at `0xF0105AFC`, with base `0x20100000`. The original interpretation that the raw cursor described the next row is therefore withdrawn.

The table, reconstructed from the mapper argument snapshots and the fourth word at each cursor address:

| Index | Record | Physical base | Size | BSE alias | Raw +0x0C |
|---:|---:|---:|---:|---:|---:|
| 0 | `0xF0105AEC` | `0x00000000` | `0x00100000` | `0x00000000` | `0x000000C4` |
| 1 | `0xF0105AFC` | `0x20100000` | `0x00100000` | `0xFFB00000` | `0x00000185` |
| 2 | `0xF0105B0C` | `0x40000000` | `0x00001000` | `0xFFC00000` | `0x00000005` |
| 3 | `0xF0105B1C` | `0x20010000` | `0x00001000` | `0xFFD10000` | `0x00000005` |
| 4 | `0xF0105B2C` | `0x20020000` | `0x00007000` | `0xFFD20000` | `0x00000405` |
| 5 | `0xF0105B3C` | `0x20030000` | `0x00007000` | `0xFFD30000` | `0x00000405` |
| 6 | `0xF0105B4C` | `0x20040000` | `0x00003000` | `0xFFD40000` | `0x00000405` |
| 7 | `0xF0105B5C` | `0x20040000` | `0x00003000` | `0xFFD40000` | `0x00000405` |
| 8 | `0xF0105B6C` | `0x20050000` | `0x00003000` | `0xFFD50000` | `0x00000405` |
| 9 | `0xF0105B7C` | `0x1C000000` | `0x00001000` | `0xFFE00000` | `0x00000405` |
| 10 | `0xF0105B8C` | `0xFFFF0400` | `0x0000FC00` | `0xFFFF0400` | `0x00000044` |
| 11 | `0xF0105B9C` | `0x00000000` | `0x00100000` | `0xFF100000` | `0x0000040D` |
| 12 | `0xF0105BAC` | `0x04000000` | `0x00100000` | `0xFF200000` | `0x0000040D` |
| 13 | `0xF0105BBC` | `0x00000001` | `0x00100000` | `0xE0001000` | `0x00000105` |

The trace shows `0xFFFFFFFF` at `0xF0105BCC` immediately after row 13's fourth word, acting as the table terminator. Rows 6 and 7 are identical across all four observed words; keep this as an observation, not a claimed firmware defect.

This is stronger than the earlier cursor-only interpretation: the first alias-0 mapping is explicitly present in the static table. The unresolved collision is between that explicit 1-MiB mapping and the later 22-MiB allocator mapping. The next task is to establish the intended reservation/extension semantics between the static table and the NOR free-range allocator. No firmware data or mapping was modified.


### 19.1 CI validation and latest static-table trace kit

For checkpoint 3c1726af079fd4bb08308757cd9c83dbe1c175d4:
- RM-217 Boot Probe #42371570722 — success on Windows and Linux; all trace summarizer tests, Rust unit tests and release builds passed.
- Android CI pull-request #42371570679 — success; Android unit tests, debug APK assembly and artifact upload passed.
- Android CI push #42371566417 — success.
- PR #1 remains open and mergeable; it has not been merged.

Latest Windows trace kit:
- Artifact: rm217-windows-bse-trace-kit, ID 13574605219.
- SHA-256: fbff4b2c30e3eca27ca13b3242cf0069dcbdb72e961e85df7e20f4a478ac9b2d.
- Size: 1,300,724 bytes; expires 2026-11-21 12:15:39 UTC.

The trace summarizer now reports the CP15 MMU state for any BSE slot-table read and reconstructs the 14 static mapping rows from the current-record cursor and its fourth word. Synthetic tests verify that cursor = table_root + index*16 + 0x0C and that active-MMU warnings are not limited to slot 0. This validates the diagnostic tooling; no new guest-firmware execution was performed in this checkpoint.

### 19.2 Next read-only BSE mapper capture

The newly restored table establishes that row 0 itself maps physical base 0, size 1 MiB and alias 0. The next unresolved detail is how the BSE mapper treats an already-populated alias slot and why the row-0 entry creates marker 0x3E, while other aliases create page-table/descriptor values.

The runner now adds:
- full Thumb disassembly of 0xF0103B00..0xF0104000, covering the BSE slot check, assertion path and mapping/allocation helpers;
- exact watchpoints at 0xF0103C3E, 0xF0103D3C, 0xF0103D5C, 0xF0103D6E and 0xF0103D7E;
- a read-only dump of 0xE4 bytes beginning at 0xF0105AEC, covering the 14 static 16-byte rows and the following FFFFFFFF terminator.

These are observation-only additions. The runner still uses the established diagnostic ASIC-ready, candidate NOR-ID and legacy-unmapped-as-RAM overrides. It does not write or patch firmware, the static table, the BSE table or the assertion.

At the time this section was written, the next execution was planned with artifact from run 42371570722. That kit has since been superseded; use the current artifact recorded in section 19.7. Retain the full trace and compact summary. The immediate analysis targets are: the meaning of raw row field +0x0C, duplicate rows 6/7, and the exact code path that distinguishes marker 0x3E from page-table-backed alias slots. Do not change mapping behaviour until that is understood.


### 19.3 Latest mapper-inspection kit and green CI (2026-11-07)

For current checkpoint ee4f82297dffc796aa0195258ff0694133824fd7:
- [RM-217 Boot Probe #42371959409](https://github.com/irman100/RetroMobileWorld/actions/runs/42371959409) — success on Windows and Linux. The updated trace runner, summarizer tests, Rust unit tests, release builds and Windows trace-kit assembly/upload passed.
- [Android CI PR #42371959417](https://github.com/irman100/RetroMobileWorld/actions/runs/42371959417) — success; Android unit tests and debug APK assembly/upload passed.
- [Android CI push #42371955441](https://github.com/irman100/RetroMobileWorld/actions/runs/42371955441) — success.
- Latest Windows artifact: rm217-windows-bse-trace-kit, ID 13573956694, SHA-256 f47242ebb8962cfd0ac08f928776a616cc9ec7abdd5f5b2e4a2405a7b1fb9a2a, 1,300,758 bytes; expires 2026-11-21 12:21:31 UTC.
- PR #1 remains open, unmerged and mergeable.

The latest runner expands Thumb disassembly to 0xF0103B00..0xF0104000, dumps 0xE4 bytes at 0xF0105AEC, and watches the marker write at 0xF0103C3E plus mapper/page-table update paths at 0xF0103D3C, 0xF0103D5C, 0xF0103D6E and 0xF0103D7E. This only changes diagnostics. No guest firmware mapping or bytes have been changed, and no new guest run has yet been performed with this expanded capture.

### 19.4 Shared relocation helper: correlate the actual allocator invocation

The full trace shows that 0xF0104976 is a shared helper with many callers. The old summary displayed the last helper-entry hit, which came from the later assertion/logging path (LR=0xF0102FB3, r0=0xF0103AB0) and was not the NOR allocator's call. This was a summary correlation defect, not an error in the guest execution.

For the NOR allocator invocation:
- call site before BL: 0xF010432A, LR=0xF0104307, SP=0x08002EC8, r0=0xF0100000;
- matching helper entry: 0xF0104976, LR=0xF010432F, SP=0x08002EC8, r0=0xF0100000;
- return site after BL: 0xF010432E, SP=0x08002EC8, r0=0xF0100000;
- after literal load: 0xF0104330, r4=0xF0100000, r0=0xF0100000;
- after SUBS: 0xF0104332, r0=0.

The summarizer now selects the helper-entry snapshot by the caller's Thumb LR 0xF010432F, rather than using the last hit for PC 0xF0104976. A synthetic regression test includes an unrelated helper invocation with different LR/SP and verifies it is ignored. The caller/entry/return register and stack chain is reported together. This makes the helper-return conclusion directly tied to the correct invocation.

### 19.5 Helper-call correlation CI and evidence preservation

The helper-correlation change was validated on checkpoint a2da0ed052787f573f072a2455c52ceb91a92f75:
- [RM-217 Boot Probe #42372700232](https://github.com/irman100/RetroMobileWorld/actions/runs/42372700232) — success on Windows and Linux; summarizer tests and Rust unit tests passed, both release builds completed, and the Windows trace kit was uploaded.
- [Android CI PR #42372700217](https://github.com/irman100/RetroMobileWorld/actions/runs/42372700217) — success.
- [Android CI push #42372697855](https://github.com/irman100/RetroMobileWorld/actions/runs/42372697855) — success.
- Windows trace-kit artifact from run 42372700232: ID 13573972459; SHA-256 e3ee10cd1ebad36d07a0a8f940af30cff4391350fd1f3c38361a7773ae5dfe2a.

The runner now backs up existing fixed-name Desktop trace and summary before starting a new run. Backups use the names `rm217-bse-alias-mmuv2.previous-<timestamp>.txt` and `rm217-bse-alias-mmuv2.previous-<timestamp>.summary.txt`; the current run continues to write the stable `rm217-bse-alias-mmuv2.txt` and `.summary.txt` names. This avoids destroying the previous evidence when the same kit is run again.

### 19.6 Same physical NOR window appears under two BSE aliases

The restored static table contains two records whose first two fields match:
- row 0: physical base `0x00000000`, length `0x00100000`, BSE alias `0x00000000`, raw `+0x0C = 0x000000C4`;
- row 11: physical base `0x00000000`, length `0x00100000`, BSE alias `0xFF100000`, raw `+0x0C = 0x0000040D`.

They therefore describe the same physical 1-MiB interval through two distinct BSE alias windows: slot `0x000` and slot `0xFF1`. The trace shows different resulting slot values: row 0 writes `0x3E`, while row 11 writes `0x472`. The raw fourth words also differ. This is direct evidence that one physical interval can be registered more than once under different BSE aliases and different attributes/representations. The exact semantics of `0xC4`, `0x40D`, `0x3E` and `0x472` remain unknown.

Because the NOR descriptor is type `0x24`, base `0`, size `0x02000000`, both row-0 and row-11 intervals lie within the described NOR window. It is reasonable to treat the two table rows as separate BSE views of the same first MiB of that physical window, but the trace alone does not prove byte-level equivalence or intended software use for each view.

This helps explain why “different kinds of memory/addressing” came up in the earlier investigation: physical NOR/SDRAM windows are distinct, and the BSE mapper separately exposes some physical intervals through more than one alias. It does not make the current assertion disappear. The failing late allocator still sends `(physical=0, BSE alias=0)` and collides with row 0's slot; it does not use row 11's alternate alias `0xFF100000`. Do not substitute that alias for the 22-MiB NOR request without establishing the firmware's intended alias policy and available range.

### 19.7 Current runner, evidence-preservation behaviour and validated artifact

For current checkpoint `9635ce53c942523c59c5a386f6e3bc109a5aad51`:
- [RM-217 Boot Probe #42373012663](https://github.com/irman100/RetroMobileWorld/actions/runs/42373012663) — success on Windows and Linux; probe unit tests, trace summarizer tests, release builds and Windows trace-kit upload passed.
- [Android CI PR #42373012770](https://github.com/irman100/RetroMobileWorld/actions/runs/42373012770) — success; Android tests and debug APK assembly/upload passed.
- [Android CI push #42373008184](https://github.com/irman100/RetroMobileWorld/actions/runs/42373008184) — success.
- Latest Windows artifact: `rm217-windows-bse-trace-kit`, ID `13574875706`, SHA-256 `983061ac50f66e87538f0d69cb0c2309395af4694d38eee55f4bb1b67473f989`, 1,301,440 bytes; expires 2026-11-21 12:35:21 UTC.

The summarizer now correlates the allocator's `0xF0104976` helper entry by `LR=0xF010432F`, preventing a later logging call to the same helper from being mistaken for the NOR allocator invocation. The runner now copies pre-existing Desktop trace and summary files to timestamped `.previous-...` names before starting a new run, then writes the new trace/summary to the stable filenames. The expanded BSE disassembly and static-table dump remain read-only diagnostics; no guest mapping, firmware bytes or assertion behaviour were changed.

### 19.8 New 6,318-line trace: BSE window sharing and reset endpoint (2026-11-07)

User-provided runner output summary:
- 6,318 lines scanned; 19 mapper calls; 16 slot-table reads; 37 slot-table writes.
- ARM CP15 SCTLR remains 0x0009117C at the late mapper hook (M=0); all 15 listed slot-table VA/PA pairs are identical in this run.
- The static table dump is present in the summary and repeats the 14 rows plus 0xFFFFFFFF terminator.
- The trace reports the guest requested a hardware reset: write 0x00000002 to 0x20032D14 at PC 0xF0103A48.
- The assertion formatter path is also observed at 0xF0103C0A, with r0=0x3E; the summary confirms the alias-0 marker 0x3E was written and later read. The summary does not by itself prove the exact control-flow edge between assertion formatting and the reset request. Host probe exit code 0 only means the probe stopped cleanly; it is not evidence of OS boot.
- Uploaded summary text file SHA-256: 5524fc6110ec286b62cb93c7e1b6eed0377315644ae6777e2288918d2cdb6e14 (23,369 bytes, 200 lines as stored).

#### 19.8.1 Important distinction: same 1-MiB BSE slot is expected for several subranges

bse_slot_index(alias) = alias >> 20 selects the 1-MiB alias window, not an individual 64-KiB subrange. The low 20 bits of the alias are the offset inside that window.

The new trace makes this explicit for rows 3-8:
- row 3: alias 0xFFD10000, offset 0x00010000, physical 0x20010000, size 0x1000;
- row 4: alias 0xFFD20000, offset 0x00020000, physical 0x20020000, size 0x7000;
- row 5: alias 0xFFD30000, offset 0x00030000, physical 0x20030000, size 0x7000;
- rows 6 and 7: both alias 0xFFD40000, offset 0x00040000, physical 0x20040000, size 0x3000;
- row 8: alias 0xFFD50000, offset 0x00050000, physical 0x20050000, size 0x3000.

All six records share BSE slot index 0xFFD, table word address 0x10093FF4, and 1-MiB alias window [0xFFD00000, 0xFFE00000). The distinct low-20-bit offsets place their requested subranges at different positions within that window. Therefore, repeated slot address for rows 3-8 is not itself a collision. The BSE slot can be the page-table root for the entire 1-MiB alias window, with individual mappings occupying different offsets within it. The slot log shows writes associated with the FFD slot at 0x10094400, 0x10094430 and 0x10094431, consistent with setup of a page-table-backed alias window; the full trace/disassembly is still needed before assigning exact structure semantics.

Rows 6 and 7 are an exact duplicate mapping request across all four observed words: same physical base, size, alias and raw +0x0C (0x405). It may be intentional repeat registration or redundant table data; do not call it corruption without examining mapper's repeated-PTE path.

This corrects any interpretation that treated every repeated 1-MiB slot as a conflict. The alias-0 situation remains a separate special case: row 0 maps (physical 0, size 1 MiB, alias 0), writes marker 0x3E into slot 0, and the late NOR allocator then requests (physical 0, size 22 MiB, alias 0) and reads the same marker.

#### 19.8.2 Same physical memory, alternate alias stays valid

Rows 0 and 11 still demonstrate the same physical first MiB of the NOR descriptor registered via separate 1-MiB BSE windows: row 0 uses alias 0 and slot 0x000; row 11 uses alias 0xFF100000 and slot 0xFF1.

The new run confirms slot 0xFF1 receives 0x472; slot 0x000 receives 0x3E. These are separate alias windows, unlike rows 3-8, which share one BSE slot but use distinct offsets within that window.

#### 19.8.3 Probe endpoint

This run does not establish OS boot. It ends when firmware writes the guest hardware-reset request (0x20032D14 <- 2) at PC 0xF0103A48. The assertion formatter observation at PC 0xF0103C0A/r0=0x3E and reset request are both present, but the summarized output alone is insufficient to prove which code path caused the reset.

#### 19.8.4 Diagnostic tooling follow-up

The summarizer now needs to render alias_offset = alias & 0x000FFFFF, group static rows by alias >> 20, and distinguish shared 1-MiB alias window with non-overlapping subranges, exact duplicate rows (6/7), and genuinely overlapping alias intervals that differ in physical range or attributes.

This prevents treating legitimate page-table sharing as a BSE collision. No firmware bytes, memory map or assertion behaviour were changed.


## 20. Existing emulator research and RM-217 hardware profile (2026-11-07)

### 20.1 Realistic reuse decision

The online survey is recorded in [RM217_EMULATOR_REUSE_RESEARCH.md](RM217_EMULATOR_REUSE_RESEARCH.md). The key result is that c3emu is already our shared CPU foundation, but its full RM-614 board is not the RM-217 board.

The current probe uses c3emu's ARM926 interpreter, MMU translation helpers and FPSX parser. It does not instantiate `c3emu::machine::Machine`. This is the correct reuse boundary: share the CPU and generic translation/container code; implement an RM-217-specific SoC/device layer.

Other reviewed references:
- Nokia DCT3 emulator — useful patterns for per-model firmware profiles, bus/event tracing and headless validation, but DCT3/MAD2/CCONT/C54x hardware is not RM-217.
- EmuGII — useful QEMU ARM926 board/device architecture, but its STMP3770 calculator board is not compatible.
- FreeJ2ME — application runtime only, not a Series 40 OS or BB5 hardware emulator.
- QEMU ARM system emulation — a possible alternative board framework, but the machine model must match RAPGSM/RM-217.

No source from these projects has been copied into the repository.

### 20.2 New hardware facts

The RM-217 service schematics identify combo memory package `K5G5629ATB-SF75` as 256-Mbit NOR + 128-Mbit DDR DRAM. The same board schematic shows RAPGSM and RETU/TAHVO connected via CBUS(3:0), while I2C(1:0) is a separate bus.

Historical, model-specific flashing records report NOR ID `00EC:2208`, Samsung `K8F5615ETM`, 256 Mbit and a 16-bit bus. They also expose physical NOR ranges: PPM starts at `0x01080000`, IMAGE/CNT at `0x01600000`, and the reported content write range ends at `0x01FDFFFF`. This is consistent with the live type-8 reservation `[0x01600000, 0x02000000)`, but it does not explain the BSE alias-zero collision.

The exact K8F5615ETM primary datasheet and full erase geometry are not yet confirmed. Do not claim a complete NOR command model based only on service-tool output and secondary part listings.

### 20.3 Functional probe change

The probe now has a named `RM217_NOR0_PROFILE` with 32-MiB capacity, 16-bit bus, service-observed ID candidate `00EC:2208` and known MCU/PPM/IMAGE physical ranges. When FPSX regions are staged into the read-only diagnostic NOR array, the probe prints which known range each region belongs to. The existing 32-MiB array size is now derived from this profile rather than being an unexplained literal.

The Rust test suite validates the PPM/IMAGE boundaries and that the final 128-KiB tail remains unclassified. This profile is metadata/region classification only: no erase/program/CFI state machine has been added yet.

### 20.4 CBUS investigation target

The board schematic shows RETU/TAHVO on CBUS(3:0), not simply on the separate I2C(1:0) bus. A generic I2C PMIC model copied from another Nokia platform would therefore be wrong. The next device-level reverse-engineering task is to identify RAPGSM's CBUS master routines and capture its first RETU/TAHVO frames.

### 20.5 Baseline validation before this profile patch

The preceding code checkpoint `b3a149c2c549d1514cff30428f2c180d0d643967` passed:
- [RM-217 Boot Probe #42374531265](https://github.com/irman100/RetroMobileWorld/actions/runs/42374531265) — Windows and Linux, trace summarizer tests, Rust tests, both release builds and Windows trace-kit.
- [Android PR #42374531245](https://github.com/irman100/RetroMobileWorld/actions/runs/42374531245) — success; debug APK assembled.
- [Android push #42374526758](https://github.com/irman100/RetroMobileWorld/actions/runs/42374526758) — success.

The NOR-profile, source-comment, README, third-party notice and research-document changes in the current patch still require a new CI run.



## 21. RETU/TAHVO CBUS: RM-217 wiring and candidate protocol (2026-11-07)

### RM-217 schematic evidence

The RM-217/RM-222 Service Schematics v2.0, page 5, name the RAPGSM-side CBUS connections:
- GENIO31 = CBusClk;
- GENIO30 = CBusDa;
- CBusEn1X = select/enable line;
- RETU-side names are CbusClk, CbusData and CbusSelX;
- the CBUS group is shared by RETU, TAHVO and FM radio.
The same page separately shows I2C(1:0), SDA and SCL. The board PMIC transport must therefore not be replaced by an ordinary I2C model.

Reference: https://usermanual.wiki/Document/nokia6300rm2176300brm222serviceschematicsv20.673614552.pdf

### Related open-source protocol implementations

The Linux Nokia Internet Tablet driver `i2c-cbus-gpio.c` (https://android.googlesource.com/kernel/common/+/e32795503de02da4e7e74a5e039cc268f6a0ecfb/drivers/i2c/busses/i2c-cbus-gpio.c) bit-bangs CLK, DAT and SEL with three GPIOs. It documents a candidate transaction header of 3-bit device address, 1-bit R/W and 5-bit register, sent MSB-first, followed by a 16-bit data phase. For reads it switches DAT to input and clocks a 16-bit word back. The Linux driver exposes this over its I2C adapter API, but physically this is GPIO-driven CBUS, not the separate I2C SDA/SCL interface.

The old QEMU/DECAF model (https://github.com/decaf-project/DECAF/blob/6b09760d9b35fa37f524e810c6561bdb4c76b821/decaf/hw/cbus.c) independently models a three-line Nokia CBUS with the same 9-bit header + 16-bit data structure and generic Retu/Tahvo devices. It is based on reverse engineering of other Nokia platforms, so its register defaults and slave addresses are references, not confirmed RM-217 behaviour.

The Linux driver specifically cautions that its 3-bit address and 5-bit register field widths should be checked for other CBUS implementations. Thus the 3-wire protocol is a strong candidate consistent with the RM-217 schematic, but it is not yet verified against live RM-217 MCUSW traffic. Do not code a full transport/PMIC model before capturing the first frame.

### Methodology reference: another Nokia reverse-engineering project

The Nokia E7-00 project https://github.com/apoage/nokia-e7-linux recovered pinmux tables and GPIO register semantics from that device's own OneNAND image and validates device models in QEMU. It targets the Broadcom BCM2727B1 / ARM1176 RAPU platform, not RM-217 RAPGSM / ARM926. It is a valuable workflow reference only; do not reuse its BCM2727 GPIO map, 7-bank registers or device code.

### Current probe instrumentation boundary

The existing watched ranges 0x20032C00..0x20032DFF and 0xFFD32C00..0xFFD32DFF are still unclassified ASIC/control windows; the reset register was observed at 0x20032D14. The current patch adds read logging to those ranges and captures the surrounding reset routine at 0xF0103A48. This is only to obtain PC/register context. The ranges are not identified as CBUS GPIO registers yet.

### 21.1.1 Validation of the CBUS-candidate read instrumentation

The code checkpoint `bf148e501034eb7191ad8678d2abd24dc5b60308` passed all CI:
- [RM-217 Boot Probe #42376324182](https://github.com/irman100/RetroMobileWorld/actions/runs/42376324182) — Linux and Windows; all summarizer/Rust tests, both release builds and Windows trace-kit succeeded.
- [Android PR #42376324180](https://github.com/irman100/RetroMobileWorld/actions/runs/42376324180) — success; debug APK artifact `13575374480`, SHA-256 `d8c4f1f443f26ba9f18babf241df8e6c7995bb6801b13e557bf5ff2c18406148`.
- [Android push #42376320051](https://github.com/irman100/RetroMobileWorld/actions/runs/42376320051) — success; debug APK artifact `13575594078`, SHA-256 `aaac5d6c89d9ebd878b985642a063312ccff1e95749af5ede1fa8e7a49f37791`.

The new [Windows trace kit](https://github.com/irman100/RetroMobileWorld/actions/runs/42376324182) is artifact `13575474418`, SHA-256 `42eb81956414bb5852fbdd15a85f76db3691657b596798f0a05f26f53b49ac44`. It adds read watchpoints over the same unclassified ASIC windows as the existing write watch and includes Thumb disassembly/watchpoints around reset request PC `0xF0103A48`. This is diagnostic only; those addresses have not been proven to be the GENIO/CBUS block.


## 22. New 6,643-line RM-217 run: MMU state, allocator arithmetic and duplicate static row (2026-11-07)

The user supplied the compact console summary generated from a new run of `run_rm217_bse_alias_trace.ps1`. The summary states that the full local trace contains 6,643 lines, 19 mapper calls, 16 slot reads and 37 slot writes. It is the **compact summary**, not the full raw trace file, so the new `ASIC-read`/`ASIC-write` events are not visible in the supplied attachment.

### 22.1 ARM MMU is disabled at the late BSE mapper

At the late mapper hook:
- SCTLR = `0x0009117C`; M bit = 0;
- TTBR = 0, DACR = 0, FCSEIDR = 0;
- observed BSE slot reads have VA=PA, including slot 0 at `0x10090000`.

This is a specific state at the late NOLO/MCUSW mapper hook; do not generalize it to every boot stage. It explains why the CPU VA/PA pairs are identical in this trace. It does **not** turn the BSE alias into an ARM MMU translation: BSE alias selection remains a separate firmware-managed table input.

### 22.2 Exact alias-zero arithmetic is now fully corroborated

At allocator call site `0xF010432A`, r0 is `0xF0100000`, r4 initially zero. The helper called at `0xF0104976` returns `0xF0100000`; the literal load at `0xF010432E` also places `0xF0100000` in r4. At `0xF0104330`, `SUBS r0,r4,r0` therefore produces zero, which is stored at SP+0x38 and passed as the BSE alias. Helper entry/return stack pointers match. No register corruption is indicated.

Type-8 range subtraction remains as before: occupied interval `[0x01600000,0x02000000)`, candidate interval after subtraction `[0x00000000,0x01600000)`. The alias-zero conflict therefore remains an allocator/mapping-policy issue, not an arithmetic or Type-8 boundary arithmetic failure.

### 22.3 A duplicate static row is confirmed but not yet causal

Static rows 06 and 07 are identical:
- physical base `0x20040000`;
- length `0x3000`;
- BSE alias `0xFFD40000`;
- fourth word `0x405`.

The mapper does not assert on this repeated row in the observed run. Treat it as duplicated static-table data or repeated registration until the lower mapper semantics are disassembled. It is not yet linked to the slot-0 assertion and is not evidence of a corrupt firmware dump.

### 22.4 Reset endpoint

The probe stopped on guest write `[0x20032D14] <- 0x00000002` at PC `0xF0103A48`. The guest assertion formatter was separately observed at `0xF0103C0A` with r0=`0x3E`. The supplied compact summary does not establish whether the reset request is a direct consequence of the assertion or a later boot policy response.

### 22.5 Reset endpoint lies inside static BSE row 05

Static row 05 defines physical base `0x20030000`, size `0x7000` and BSE alias base `0xFFD30000`. Its physical interval is `[0x20030000, 0x20037000)`. The observed reset write address `0x20032D14` lies inside this interval at offset `0x2D14`; the corresponding address inside this row's alias view is `0xFFD32D14`.

This is a geometry-derived alias address. The supplied summary confirms the direct physical write at `0x20032D14`; it does not prove that the guest also performed a write through `0xFFD32D14`, nor does it identify this window as CBUS GPIO.

### 22.6 ASIC accesses are now present in compact summaries

The trace summarizer now parses `[ASIC-read NNN]` and `[ASIC-write NNN]` events. It reports counts and a bounded chronological sample: all events when there are at most 64, otherwise the first 32 and last 32. Each row includes address, access size, value and guest PC. The report separately highlights accesses at `0x20032D14` and `0xFFD32D14`.

The section is deliberately titled **UNCLASSIFIED ASIC CONTROL-WINDOW ACCESSES** and explicitly says it does not identify these registers as CBUS or GENIO. The full raw trace is still required to correlate GPIO transitions, instructions and the first CBUS frames.

### 22.7 Validation of the updated summarizer

Code checkpoint `8d049de7d59017084f8e249f3ba81079b2cf0b61` passed:
- [RM-217 Boot Probe #42379564262](https://github.com/irman100/RetroMobileWorld/actions/runs/42379564262) — Windows/Linux summarizer tests, Rust tests, release builds and Windows trace-kit.
- [Android PR #42379564321](https://github.com/irman100/RetroMobileWorld/actions/runs/42379564321) — success, debug APK assembled.
- [Android push #42379559302](https://github.com/irman100/RetroMobileWorld/actions/runs/42379559302) — success, debug APK assembled.

Latest Windows trace-kit: artifact `13577250544`, SHA-256 `a98694d3af681199f39c4d5ba1743cdc03ec5ab8c8e3cfc8097aa0f5ac5034a3`, 1,304,228 bytes, expires 2026-11-21 14:03:12 UTC.

Android debug APK artifacts: PR `13577136887`, SHA-256 `059d16e012eca85a35d644a14241e869b92e4c013987dd051c13a4b4961f1bc2`; push `13577331086`, SHA-256 `e4a9bb91bf1b6b05d0e989a4c5c9ca213e04bf1c711bc278746981db328328d2`.

Raw evidence needed to finish CBUS reconstruction remains the full local file `rm217-bse-alias-mmuv2.txt` (6,643 lines), not the 213-line console summary. The compact report can now show ASIC read/write events, but the full trace is required to inspect the instruction-by-instruction context around them.
