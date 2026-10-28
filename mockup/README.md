# Выгрузка холста

Файлы макета с холста claude.ai (Design-артефакт «Ampulse — макет») на 29.10.2026.

- `canvas.json` — доски (имя файла, место, размер, заголовок, страница `ru` или `en`), порядок, заметки и
  страницы холста.
- `<Экран>.dc.html` — один экран: разметка `<x-dc>` с подстановками `{{…}}`, `sc-for` / `sc-if` и логика
  `class Component extends DCLogic`. Отдельно в браузере не открываются: им нужна среда холста
  (`support.js`).

## Имена файлов

- Экраны 1–25: `X` — светлый русский, `XEn`, `XDark`, `XDarkEn` — копии.
- Экраны 26 и дальше: компонент `X` с пропсами `theme` (`light` / `dark`) и `lang` (`ru` / `en`), а
  `XEn`, `XDark`, `XDarkEn` — обёртки `<dc-import>`. У `ShotGuide` ещё пропс `kind` (`sc` — подкожно, `im` —
  в мышцу), вариант «в мышцу» — `ShotGuideIm*`.
- `ImportReview` и `ImportPlan` — обёртки `ImportFlow` с начальным шагом; `PetInjectionDog` — обёртка
  `PetInjection` с собакой.

Все данные в макете выдуманные. Список экранов — [docs/screens.md](../docs/screens.md).
