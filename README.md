# WMS Helper (тестовая версия 0.6.0)

Android-приложение: открывает stroeh.coglas.com в WebView и умеет:
- **Разведка** — список видимых кнопок/ссылок/полей текущей страницы (только чтение)
- **Текст** — весь видимый текст страницы
- **Лог** — перехват запросов сайта к серверу (fetch/XHR): адрес, метод, статус, кусочек данных.
  Данные входа, пароли и токены не записываются.
- **Нажать** — нажимает кнопку/пункт меню по тексту (например «Lager», «Bestellungen»)

Кнопки «Копировать» и «Поделиться» отправляют результат мне в чат.

## Загрузка на GitHub через Termux
```
termux-setup-storage
pkg install -y git unzip
cd ~
unzip ~/storage/downloads/WmsHelper.zip -d WmsHelper_tmp
cd WmsHelper_tmp/WmsHelper
git init -b main
git add .
git commit -m "WMS Helper 0.6.0"
git remote add origin https://github.com/ИМЯ/РЕПО.git
git push -u origin main
```
После push: GitHub → Actions → Build APK → Artifacts → WmsHelper-apk.
