package com.hubra.app.ui

/** Tab roots and nested destinations share one back-stack contract. */
data class NavigationState(val selectedTab: String = "chat", val stack: List<String> = emptyList()) {
    val route: String get() = stack.lastOrNull() ?: selectedTab
    val screenKey: ScreenKey get() = key(route)
    fun tab(id: String) = copy(selectedTab = id, stack = emptyList())
    fun push(destination: String) = if (route == destination) this else copy(stack = stack + destination)
    fun back(): NavigationState? = when {
        stack.isNotEmpty() -> copy(stack = stack.dropLast(1))
        selectedTab != "chat" -> tab("chat")
        else -> null
    }
}
