package ru.lager.app.ui

enum class Lang(val code: String, val title: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    DE("de", "Deutsch");

    companion object {
        fun fromCode(code: String?): Lang = entries.firstOrNull { it.code == code } ?: RU
    }
}

/** Строка на трёх языках. */
class L(private val ru: String, private val en: String, private val de: String) {
    operator fun invoke(lang: Lang): String = when (lang) {
        Lang.RU -> ru
        Lang.EN -> en
        Lang.DE -> de
    }
}

object Str {
    val searchHint = L("Поиск товара", "Search item", "Artikel suchen")
    val noData = L("Нет данных.", "No data.", "Keine Daten.")
    val inDevelopment = L("окно в разработке", "window under development", "Fenster in Entwicklung")
    val scannerSoon = L(
        "Сканер штрихкодов — в разработке",
        "Barcode scanner — under development",
        "Barcode-Scanner — in Entwicklung",
    )

    val profile = L("Профиль", "Profile", "Profil")
    val biometric = L("Вход по биометрии", "Biometric login", "Biometrische Anmeldung")
    val biometricSub = L(
        "Отпечаток или лицо вместо пароля",
        "Fingerprint or face instead of password",
        "Fingerabdruck oder Gesicht statt Passwort",
    )
    val logout = L("Выйти из аккаунта", "Sign out", "Abmelden")
    val close = L("Закрыть", "Close", "Schließen")
    val roleAdmin = L("Администратор", "Administrator", "Administrator")
    val roleObserver = L("Наблюдатель", "Observer", "Beobachter")
    val roleUser = L("Сотрудник склада", "Warehouse employee", "Lagermitarbeiter")

    // Плитки главного окна 1
    val receiving = L("Приём товаров", "Receiving", "Wareneingang")
    val inventory = L("Инвентар.", "Inventory", "Inventur")
    val bWare = L("B-Ware", "B-Ware", "B-Ware")
    val byTask = L("По заданию", "By task", "Nach Auftrag")

    // Плитки главного окна 2
    val history = L("История", "History", "Verlauf")
    val catalog = L("Каталог", "Catalog", "Katalog")
    val importExport = L("Импорт / Экспорт", "Import / Export", "Import / Export")
    val settings = L("Настройки", "Settings", "Einstellungen")
    val itemBarcode = L("Товар+ШК", "Item+Barcode", "Artikel+Barcode")
    val tasks = L("Задачи", "Tasks", "Aufgaben")
    val info = L("Инфо", "Info", "Info")
    val documents = L("Документы", "Documents", "Dokumente")
    val geminiChat = L("Чат Gemini", "Gemini chat", "Gemini-Chat")
}
