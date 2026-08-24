package com.nothingplayer.app.ui.component

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.nothingplayer.app.expect.ui.PlatformBackdrop
import com.nothingplayer.app.ui.icon.Home
import com.nothingplayer.app.ui.icon.LibraryMusic
import com.nothingplayer.app.ui.icon.Search
import com.nothingplayer.app.ui.icon.SimpIcons
import com.nothingplayer.app.ui.navigation.destination.home.HomeDestination
import com.nothingplayer.app.ui.navigation.destination.library.LibraryDestination
import com.nothingplayer.app.ui.navigation.destination.search.SearchDestination
import com.nothingplayer.app.viewModel.SharedViewModel
import org.jetbrains.compose.resources.StringResource
import nothingplayer.composeapp.generated.resources.Res
import nothingplayer.composeapp.generated.resources.home
import nothingplayer.composeapp.generated.resources.library
import nothingplayer.composeapp.generated.resources.search
import kotlin.reflect.KClass

@Composable
expect fun LiquidGlassAppBottomNavigationBar(
    startDestination: Any = HomeDestination,
    navController: NavController,
    backdrop: PlatformBackdrop,
    viewModel: SharedViewModel,
    isScrolledToTop: Boolean = false,
    onOpenNowPlaying: () -> Unit = {},
    reloadDestinationIfNeeded: (KClass<*>) -> Unit = { _ -> },
)

sealed class BottomNavScreen(
    val ordinal: Int,
    val destination: Any,
    val title: StringResource,
    val icon: @Composable () -> Unit,
) {
    data object Home : BottomNavScreen(
        ordinal = 0,
        destination = HomeDestination,
        title = Res.string.home,
        icon = {
            Icon(
                SimpIcons.Home,
                contentDescription = null,
            )
        },
    )

    data object Search : BottomNavScreen(
        ordinal = 1,
        destination = SearchDestination,
        title = Res.string.search,
        icon = {
            Icon(
                SimpIcons.Search,
                contentDescription = null,
            )
        },
    )

    data object Library : BottomNavScreen(
        ordinal = 2,
        destination = LibraryDestination,
        title = Res.string.library,
        icon = {
            Icon(
                imageVector = SimpIcons.LibraryMusic,
                contentDescription = null,
            )
        },
    )
}