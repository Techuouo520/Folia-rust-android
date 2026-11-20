package io.github.techuouo520.folia.spatial


internal object DailySpatialProtocol {
    const val VERSION:Int = 7
    const val HEADER:Int = 32
    const val STRIDE:Int = 16
    const val VISIBLE:Int = 384
    const val SLOTS:Int = 2048
    const val PREFETCH:Int = 192
    const val ACTION:Int = HEADER + STRIDE * SLOTS + PREFETCH
    const val RHINE:Int = ACTION + 192
    const val RHINE_POSES:Int = RHINE + 64
    const val MAP_CAPTIONS:Int = RHINE_POSES // Map-only native text metrics, stride 16.
    const val FLOATS:Int = RHINE_POSES + SLOTS * 16
    const val RHINE_TOPOLOGY:Int = 7 // Relative to RHINE; changes only when slot identities change.
    // GL-owned render hints, never scene/animation inputs. Full padded viewport in dp.
    const val RHINE_CLIP_LEFT:Int = 19
    const val RHINE_CLIP_TOP:Int = 20
    const val RHINE_CLIP_RIGHT:Int = 21
    const val RHINE_CLIP_BOTTOM:Int = 22
    const val RHINE_CLIP_ENABLED:Int = 23
    const val RHINE_HDR_HEADROOM:Int = 24 // GL display allowance, >=1; zero defaults to SDR in Rust.
    // Face planes: cell/song, origin x/y, local w/h, alpha, xx, interactive, xy, yx, yy.
    const val RHINE_HOLOGRAM:Int = 25
    const val HOLOGRAM_STRIDE:Int = 12
    const val HOLOGRAM_SLOTS:Int = 3
    // Context offsets are relative to ACTION. Legacy slots retain their physical offsets.
    const val MENU_ALPHA:Int = 0
    const val BLUR_MIX:Int = 1
    const val CONFIRM_ALPHA:Int = 2
    const val PHASE:Int = 3
    const val CELL:Int = 4
    const val SONG:Int = 5
    const val BOUNDS:Int = 6
    const val BLUR_RADIUS:Int = 20
    const val CORNER_RADIUS:Int = 21
    const val PLAYING_CELL:Int = 22
    const val OWNER_SLOT:Int = 23
    const val LIFT_MIX:Int = 24
    const val CAPTURE_HOLD:Int = 26
    const val LIFT_ACTIVE:Int = 27
    const val TARGET_RECT:Int = 28
    const val CONTOUR:Int = 32
    const val ACTION_RECTS:Int = 96
    const val WORLD_REVISION:Int = 124
    const val ABI_VERSION:Int = 125
    const val SHADOW:Int = 126
    const val BACKGROUND_SCALE:Int = 127
    const val STYLE_ALPHA:Int = 128 // Runtime-owned nonlinear style handoff; independent of list mode.
    const val ARRIVAL_ACTIVE:Int = 129 // Collection card admission gates artwork demand.
    const val ARRIVAL_REVISION:Int = 130 // Changes when new spatial cells are admitted.
    const val PLAYING_OPEN_PROGRESS:Int = 191
    const val CLOSED:Int = 0
    const val MENU:Int = 1
    const val CONFIRM:Int = 2
    const val DUST:Int = 3
}
