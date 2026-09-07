package com.bitperfect.android.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The bottom tabs, and the invariant that crashed the app once.
 *
 * The player used to be `Screen.Player`, a navigation destination like any other.
 * When it became a draggable surface layered over the graph, its destination was
 * removed — but `navigate(Screen.Player.route)` still compiled, because the object
 * still existed and still had a route. Tapping a track in the library therefore threw
 * `IllegalArgumentException: Navigation destination ... cannot be found in the
 * navigation graph`, on a release that otherwise built and passed every test.
 *
 * The fix was not to correct that one call. It was to delete the object, so the line
 * cannot be written: a tab with no route has nothing to pass to `navigate`. These
 * tests pin the shape that makes that true, since it is the shape rather than any
 * single call site that provides the guarantee.
 */
@DisplayName("BottomNavItem Tests")
class BottomNavItemTest {

    @Test
    @DisplayName("the player tab has no route, so it cannot be navigated to")
    fun playerHasNoRoute() {
        // The whole point. If this ever gains a route, the crash above becomes
        // possible again.
        assertNull(BottomNavItem.Player.screen)
        assertFalse(BottomNavItem.Player.isNavigable)
    }

    @Test
    @DisplayName("the other tabs are real destinations")
    fun otherTabsAreNavigable() {
        assertEquals(Screen.Library, BottomNavItem.Library.screen)
        assertEquals(Screen.Settings, BottomNavItem.Settings.screen)

        assertTrue(BottomNavItem.Library.isNavigable)
        assertTrue(BottomNavItem.Settings.isNavigable)
    }

    @Test
    @DisplayName("no tab claims the retired player route")
    fun noTabUsesThePlayerRoute() {
        // Belt and braces: catches someone re-adding `Screen("player")` and wiring
        // the tab to it rather than to the surface.
        for (item in BottomNavItem.entries) {
            assertFalse(
                item.screen?.route == "player",
                "${item.name} points at a player route again"
            )
        }
    }

    @Test
    @DisplayName("every navigable tab has a usable route")
    fun navigableTabsHaveRoutes() {
        for (item in BottomNavItem.entries.filter { it.isNavigable }) {
            val route = item.screen?.route
            assertTrue(!route.isNullOrBlank(), "${item.name} has a blank route")
            // A route with an unfilled placeholder cannot be navigated to directly,
            // which is exactly the sort of thing that only shows up when tapped.
            assertFalse(route!!.contains("{"), "${item.name} route needs arguments: $route")
        }
    }

    @Test
    @DisplayName("there are three tabs, each labelled")
    fun tabsAreLabelled() {
        assertEquals(3, BottomNavItem.entries.size)

        for (item in BottomNavItem.entries) {
            assertTrue(item.label.isNotBlank(), "${item.name} has no label")
        }
        assertEquals(
            BottomNavItem.entries.size,
            BottomNavItem.entries.map { it.label }.distinct().size,
            "two tabs share a label"
        )
    }

    @Test
    @DisplayName("tab order is Player, Library, Settings")
    fun tabOrder() {
        // The order is what the user sees, so it is worth stating rather than
        // leaving to declaration order nobody checks.
        assertEquals(
            listOf(BottomNavItem.Player, BottomNavItem.Library, BottomNavItem.Settings),
            BottomNavItem.entries.toList()
        )
    }

    @Test
    @DisplayName("entries are fully constructed, unlike the list they replaced")
    fun entriesAreNotNull() {
        // The old `bottomNavItems` had to be `by lazy`: an eager `listOf(Player, …)`
        // in Screen's companion could read a subclass object mid-construction and
        // yield a list containing null, which type erasure hid until dereferenced.
        // Enum entries cannot do that, and this asserts it rather than assuming it.
        for (item in BottomNavItem.entries) {
            assertTrue(item.name.isNotBlank())
        }
    }
}
