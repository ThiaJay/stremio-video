package org.pss.stremio.tv

import java.time.Instant

private var assertions = 0
private fun checkCase(value: Boolean, message: String) {
    assertions++
    check(value) { "FAIL: $message" }
}

fun main() {
    val coordinator = TvSkipCoordinator()
    val base = CoreSkipSegment(SkipKind.Intro, 23, "episode-A", 8000, 37000, 100000, SkipMode.Ask, true, false, 37000)
    val playing = TvPlaybackState(23, "episode-A", 11000, 100000, loaded = true)
    val offer = coordinator.presentation(base, playing)
    checkCase(offer?.label == "Skip Intro", "TV skip intro prompt present")
    checkCase(coordinator.manual(base, playing, offer?.focusedIdentity, "DPAD_DOWN") == null, "navigation does not skip")
    checkCase(coordinator.manual(base, playing, null, "ENTER") == null, "unknown focus fails closed")
    val seek = coordinator.manual(base, playing, offer?.focusedIdentity, "DPAD_CENTER")
    checkCase(seek?.seekWithinCurrentMediaMs == 37000L, "skip uses exact milliseconds")
    checkCase(seek?.dismissCoreAction?.get("action") == "Player", "Core dismiss action routed to Player")
    val nested = seek?.dismissCoreAction?.get("args") as? Map<*, *>
    checkCase(nested?.get("action") == "DismissSkipSegment", "Core action name matches source")
    checkCase(coordinator.manual(base, playing, offer?.focusedIdentity, "ENTER") == null, "no duplicate skip")
    checkCase(coordinator.presentation(base, playing) == null, "rewind does not revive consumed intro")

    val recap = base.copy(kind = SkipKind.Recap, fromMs = 0, toMs = 7000, seekToMs = 7000)
    val recapPlaying = playing.copy(positionMs = 1500)
    checkCase(coordinator.presentation(recap, recapPlaying)?.label == "Skip Recap", "recap is first class")
    val pausedAuto = base.copy(mode = SkipMode.Always)
    fun freshAuto(s: CoreSkipSegment, p: TvPlaybackState) = TvSkipCoordinator().automatic(s, p)
    checkCase(freshAuto(pausedAuto, playing.copy(paused = true)) == null, "paused automatic skip disallowed")
    checkCase(freshAuto(pausedAuto, playing.copy(generation = 24)) == null, "stale generation fails closed")
    checkCase(freshAuto(pausedAuto, playing.copy(nextVideoPopupOpen = true)) == null, "next video popup priority")
    checkCase(freshAuto(pausedAuto, playing.copy(menuOpen = true)) == null, "open menus block automatic skip")
    checkCase(freshAuto(pausedAuto, playing.copy(buffering = true)) == null, "buffering blocks auto")
    checkCase(freshAuto(pausedAuto, playing.copy(seeking = true)) == null, "active seek blocks auto")
    checkCase(freshAuto(pausedAuto, playing.copy(switchingSource = true)) == null, "source switch blocks auto")
    checkCase(freshAuto(pausedAuto, playing.copy(videoId = "episode-B")) == null, "wrong episode blocks auto")
    checkCase(freshAuto(pausedAuto, playing.copy(durationMs = 110000)) == null, "duration drift blocks auto")
    checkCase(freshAuto(pausedAuto, playing.copy(positionMs = 95000)) == null, "outside segment blocks auto")
    checkCase(freshAuto(pausedAuto.copy(mode = SkipMode.Never), playing) == null, "never skips")
    checkCase(freshAuto(pausedAuto.copy(seekToMs = null), playing) == null, "unknown seek target fails closed")
    checkCase(freshAuto(pausedAuto.copy(fromMs = 50000), playing) == null, "invalid boundaries fail closed")
    checkCase(freshAuto(pausedAuto.copy(kind = SkipKind.Preview), playing) == null, "unknown preview not actuated")
    val creditsAtEnd = base.copy(kind = SkipKind.Outro, mode = SkipMode.Always, fromMs = 80000, toMs = 100000, seekToMs = 100000)
    checkCase(freshAuto(creditsAtEnd, playing.copy(positionMs = 85000)) == null, "credits cannot synthesize end event")
    val creditsSafe = creditsAtEnd.copy(toMs = 94000, seekToMs = 94000)
    checkCase(freshAuto(creditsSafe, playing.copy(positionMs = 85000))?.seekWithinCurrentMediaMs == 94000L, "safe credits seek")
    checkCase(freshAuto(pausedAuto, playing)?.seekWithinCurrentMediaMs == 37000L, "valid automatic skip acts only once and in current media")
    val newEpisode = base.copy(generation = 25)
    val newPlayer = playing.copy(generation = 25)
    checkCase(coordinator.presentation(newEpisode, newPlayer) != null, "consumption resets only on new generation")
    checkCase(coordinator.presentation(base, playing) == null, "late older playback generation may not reset controller")

    val canonical = listOf(
        CanonicalEpisode("s1e1", 1, 1, "2025-01-01T00:00:00Z"),
        CanonicalEpisode("s1e2", 1, 2, "2025-01-08T00:00:00Z"),
        CanonicalEpisode("s2e1", 2, 1, "2025-02-01T00:00:00Z"),
        CanonicalEpisode("s2e2", 2, 2, "2025-02-08T00:00:00Z"),
        CanonicalEpisode("s3e1", 3, 1, "2035-02-01T00:00:00Z"),
        CanonicalEpisode("special", 0, 1, "2024-01-01T00:00:00Z"),
        CanonicalEpisode("extra", 0, 2, "2024-01-01T00:00:00Z")
    )
    val before = canonical.toList()
    val state = SeriesAccountSnapshot("a".repeat(64), "b".repeat(64), "tt123", "tt123", true, true,
        canonical, listOf("s1e1", "special", "s1e2", "s2e1", "s2e2", "s3e1"), 1,
        Instant.parse("2026-10-12T13:00:00Z"))
    val watched = TvWatchedCoordinator()
    val title = watched.prepare(state, WatchedScope.Series, true)
    checkCase(title != null && title.selectedIds == listOf("s1e1", "s1e2", "s2e1", "s2e2", "special"), "released story excludes future and ancillary")
    checkCase(watched.prepare(state, WatchedScope.Season(3), true) == null, "future season watched refused")
    checkCase(watched.prepare(state, WatchedScope.Season(3), false) == null, "future season unwatched refused")
    checkCase(watched.prepare(state, WatchedScope.Season(0), true) == null, "season zero refused")
    checkCase(watched.prepare(state, WatchedScope.Season(-1), true) == null, "negative season refused")
    val season = watched.prepare(state, WatchedScope.Season(2), true)
    checkCase(season?.selectedIds == listOf("s2e1", "s2e2"), "canonical season scope")
    checkCase(watched.confirm(season!!, state, TvWatchedConfirmation(true,"DPAD_DOWN",season.fingerprint)) == null, "no mutation on focus navigation")
    checkCase(watched.confirm(season, state, TvWatchedConfirmation(false,"ENTER",season.fingerprint)) == null, "no action without focus")
    checkCase(watched.confirm(season, state.copy(libraryItemFingerprint = "c".repeat(64)), TvWatchedConfirmation(true,"ENTER",season.fingerprint)) == null, "stale watched state fails closed")
    checkCase(watched.confirm(season, state.copy(accountFingerprint = "d".repeat(64)), TvWatchedConfirmation(true,"ENTER",season.fingerprint)) == null, "account switch fails closed")
    val confirmed = watched.confirm(season, state, TvWatchedConfirmation(true,"ENTER",season.fingerprint))
    val nestedAction = confirmed?.coreAction?.get("args") as? Map<*, *>
    checkCase(nestedAction?.get("action") == "MarkSeasonAsWatched", "exact Core season action")
    checkCase(nestedAction?.get("args") == listOf(2,true), "exact season action args")
    checkCase(watched.confirm(season, state, TvWatchedConfirmation(true,"ENTER",season.fingerprint)) == null, "one-use confirmation")
    val futureUnknown = state.copy(videos = canonical.map { if (it.id == "s2e2") it.copy(releasedAt = null) else it })
    checkCase(watched.prepare(futureUnknown, WatchedScope.Season(2), true) == null, "undated season fails closed")
    checkCase(watched.prepare(futureUnknown, WatchedScope.Season(2), false) == null, "undated season unwatch fails closed")
    val remove = watched.prepare(state, WatchedScope.Season(1), false)!!
    checkCase(watched.confirm(remove,state,TvWatchedConfirmation(true,"ENTER",remove.fingerprint)) == null,"unwatch separate acknowledgement")
    checkCase(watched.confirm(remove,state,TvWatchedConfirmation(true,"ENTER",remove.fingerprint,true))?.coreAction?.get("action") == "MetaDetails", "confirmed unwatched uses MetaDetails")
    checkCase(watched.prepare(state.copy(mediaType="movie"),WatchedScope.Series,true) == null,"non series metadata refused")
    checkCase(watched.prepare(state.copy(metaReady=false),WatchedScope.Series,true) == null,"no metadata fails closed")
    checkCase(watched.prepare(state.copy(libraryReady=false),WatchedScope.Series,true) == null,"no library readiness fails closed")
    checkCase(watched.prepare(state.copy(readyMetaId="wrong"),WatchedScope.Series,true) == null,"wrong canonical identity")
    checkCase(watched.prepare(state.copy(accountFingerprint="bad"),WatchedScope.Series,true) == null,"account fingerprint required")
    checkCase(watched.prepare(state.copy(videos=canonical+canonical.first()),WatchedScope.Series,true) == null,"duplicate ID refused")
    checkCase(watched.prepare(state.copy(storyOrder=listOf("s1e1","extra")),WatchedScope.Series,true)?.selectedIds?.contains("special") == false,"invalid Story Order fails to ordinary episodes")
    checkCase(canonical == before, "synthetic metadata unchanged")
    println("PASS: $assertions native TV skip and watched action safety assertions")
}
