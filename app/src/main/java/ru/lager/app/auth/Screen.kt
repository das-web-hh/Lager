package ru.lager.app.auth

enum class Role { ADMIN, OBSERVER, USER }

sealed interface Screen {
    data object Login : Screen
    data object Register1 : Screen
    data object Register2 : Screen
    data object Register3 : Screen
    data object Reset1 : Screen
    data object Reset2 : Screen
    data object Lock : Screen
    data class Home(val role: Role) : Screen
}

data class ToastMsg(val id: Long, val text: String, val isError: Boolean)
