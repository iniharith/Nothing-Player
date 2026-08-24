package com.nothingplayer.app.di

import com.nothingplayer.app.viewModel.AlbumViewModel
import com.nothingplayer.app.viewModel.AnalyticsViewModel
import com.nothingplayer.app.viewModel.ArtistViewModel
import com.nothingplayer.app.viewModel.HomeViewModel
import com.nothingplayer.app.viewModel.ImportViewModel
import com.nothingplayer.app.viewModel.LibraryDynamicPlaylistViewModel
import com.nothingplayer.app.viewModel.LibraryViewModel
import com.nothingplayer.app.viewModel.LocalPlaylistViewModel
import com.nothingplayer.app.viewModel.LogInViewModel
import com.nothingplayer.app.viewModel.MoodViewModel
import com.nothingplayer.app.viewModel.MoreAlbumsViewModel
import com.nothingplayer.app.viewModel.NotificationViewModel
import com.nothingplayer.app.viewModel.NowPlayingBottomSheetViewModel
import com.nothingplayer.app.viewModel.PlaylistViewModel
import com.nothingplayer.app.viewModel.PodcastViewModel
import com.nothingplayer.app.viewModel.RecentlySongsViewModel
import com.nothingplayer.app.viewModel.SearchViewModel
import com.nothingplayer.app.viewModel.SettingsViewModel
import com.nothingplayer.app.viewModel.SharedViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule =
    module {
        single {
            SharedViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        single {
            SearchViewModel(
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            NowPlayingBottomSheetViewModel(
                get(),
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            LibraryViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            LibraryDynamicPlaylistViewModel(
                get(),
                get(),
            )
        }
        viewModel {
            ImportViewModel(
                get(),
            )
        }
        viewModel {
            AlbumViewModel(
                get(),
                get(),
            )
        }
        viewModel {
            HomeViewModel(
                get(),
                get(),
            )
        }
        viewModel {
            SettingsViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            ArtistViewModel(
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            PlaylistViewModel(
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            LogInViewModel(
                get(),
            )
        }
        viewModel {
            PodcastViewModel(
                get(),
            )
        }
        viewModel {
            MoreAlbumsViewModel(
                get(),
            )
        }
        viewModel {
            RecentlySongsViewModel(
                get(),
            )
        }
        viewModel {
            LocalPlaylistViewModel(
                get(),
                get(),
                get(),
            )
        }
        viewModel {
            NotificationViewModel(
                get(),
            )
        }
        viewModel {
            MoodViewModel(
                get(),
                get(),
            )
        }
        viewModel {
            AnalyticsViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }