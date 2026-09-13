package com.mediacontrol.remote.data

/** Implemented by each app's Application class so the shared [MediaListenerService] can reach it. */
interface RepoHost {
    val repo: MediaRemoteRepository
}
