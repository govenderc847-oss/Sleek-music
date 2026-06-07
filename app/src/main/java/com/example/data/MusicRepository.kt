package com.example.data

import kotlinx.coroutines.flow.Flow

class MusicRepository(private val musicDao: MusicDao) {
    val favoriteTracks: Flow<List<FavoriteTrack>> = musicDao.getFavoriteTracks()
    val userPlaylists: Flow<List<UserPlaylist>> = musicDao.getUserPlaylists()

    suspend fun addFavorite(trackId: String) {
        musicDao.insertFavorite(FavoriteTrack(trackId))
    }

    suspend fun removeFavorite(trackId: String) {
        musicDao.deleteFavorite(trackId)
    }

    suspend fun createPlaylist(name: String): Long {
        return musicDao.insertPlaylist(UserPlaylist(name = name))
    }

    suspend fun deletePlaylist(playlistId: Int) {
        musicDao.deletePlaylist(playlistId)
    }

    fun getTracksForPlaylist(playlistId: Int): Flow<List<PlaylistTrack>> {
        return musicDao.getTracksForPlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Int, trackId: String) {
        musicDao.insertTrackToPlaylist(PlaylistTrack(playlistId = playlistId, trackId = trackId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Int, trackId: String) {
        musicDao.deleteTrackFromPlaylist(playlistId, trackId)
    }
}
