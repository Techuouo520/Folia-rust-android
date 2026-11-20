//! Flat frame ABI. Units are logical display points; song and cell IDs are distinct.
pub const HEADER: usize = 32;
pub const STRIDE: usize = 16;
pub const VISIBLE: usize = 384;
// Pad landscape retains the full camera field and its optical/camera guard.
// This is capacity only: rendering, hits and uploads use the published count.
pub const SLOTS:usize=2048;
pub const PREFETCH: usize = 192;
pub const ACTION:usize=HEADER+STRIDE*SLOTS+PREFETCH;
// Relative to ACTION. Actual opening displacement of the published playing instance.
pub const PLAYING_OPEN_PROGRESS:usize=191;
pub const RHINE:usize=ACTION+192;
// Hologram face planes in the reserved Rhine header. Each retained slot is
// cell, song, origin x/y, local width/height, alpha, xx, interactive, xy, yx, yy.
pub const RHINE_HOLOGRAM:usize=25;
pub const HOLOGRAM_STRIDE:usize=12;
pub const HOLOGRAM_SLOTS:usize=3;
pub const RHINE_POSES:usize=RHINE+64;
// In map mode the unused pose slots carry native caption width, height and font scale.
// The 16-float slot stride keeps the buffer size unchanged; Rhine still owns its matrices.
pub const MAP_CAPTIONS:usize=RHINE_POSES;
pub const FLOATS: usize = RHINE_POSES+SLOTS*16;
pub const VERSION:u32=7;

/// Reserve bit 1 of the packed artwork-ready field for the expanded playing sleeve.
pub(crate) fn lyrics_chip_flag(frame: &[f32], cell: f32) -> f32 {
    if frame.get(ACTION + PLAYING_OPEN_PROGRESS).is_some_and(|&open| open >= 0.98)
        && frame[ACTION + 22] == cell && cell >= 0. && frame[ACTION + 3] == 0. {
        2.
    } else { 0. }
}
