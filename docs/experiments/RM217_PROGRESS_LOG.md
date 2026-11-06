# Журнал продолжения: эмуляция Nokia 6300 RM-217

**Назначение:** единая точка восстановления контекста для следующих сессий. Перед продолжением прочитать этот файл, затем актуальные исходники и перечисленные трассы. Не повторять выполненные эксперименты и не принимать диагностические подстановки за модель реального оборудования.

**Обновлено:** 6 ноября 2026  
**Ветка:** `feature/j2me-library-bridge-20261104`  
**HEAD валидированного probe-кода:** `324719a3b387d596fb96dc5542c48df9795ffbfb`  
**PR:** [#1](https://github.com/irman100/RetroMobileWorld/pull/1), открыт, не слит.  
**CI для текущего HEAD `304b297`: успешно:**
- [Android CI #42268498333](https://github.com/irman100/RetroMobileWorld/actions/runs/42268498333)
- [RM-217 Boot Probe #42268498270](https://github.com/irman100/RetroMobileWorld/actions/runs/42268498270) — Linux и Windows build/test

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

### 14.4 Важное уточнение: raw descriptor pointer опережает mapper arguments

Не считать `r3` на hook `0xF0103ECE` указателем на raw descriptor, который породил текущие `r0/r1/r2`, без отдельной проверки.

В trace виден устойчивый сдвиг на одну запись:

- mapper hit/index 0 передаёт `(base=0, size=1 MiB, alias=0)`, но `r3=0xF0105AF8` указывает на raw words `000000C4 20100000 00100000 FFB00000`; эти поля соответствуют следующему mapping request (hit/index 1: `0x20100000, 1 MiB, 0xFFB00000));
- hit/index 1 передаёт `0x20100000, 1 MiB, 0xFFB00000`, а raw data по `r3=0xF0105B08` уже описывает следующий вызов `0x40000000, 4 KiB, 0xFFC00000);
- аналогичный сдвиг продолжается по последующим entries.

Таким образом, первый mapping alias 0 — отдельный prelude/начальная запись в последовательности статической инициализации, предшествующая обработке raw table entries (или эквивалентный шаг с уже сдвинутым указателем). Точное место, которое создаёт этот prelude mapping, ещё нужно доказать по caller code. **Не называть raw entry по адресу `0xF0105AF8` источником alias-0 mapping.**

В то же время raw descriptor, который приводит к отображению физической базы 0 через alias `0xFF100000`, действительно занимает отдельный slot:
- BSE alias index `0xFF1`;
- slot address `0x10093FC4`;
- при его создании slot был пуст (`0`) и затем получил marker `0x472`.

Это подтверждает, что два отображения физического base 0 — alias 0 и alias `0xFF100000` — не конфликтуют между собой. Конфликтует именно дополнительный поздний NOR request с alias 0.

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
