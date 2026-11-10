// Cover-wall camera and the two layer changes. Numbers are the desktop wall's, which copies the original.
// Drag threshold, release speed and the inertia time constant are `wallPanMotion.ts` / `wall_pan.rs`.
// The wave and the flip are `bravaisWallWave.ts`, `flipPlan.ts` and `wall_shift.rs`. Wheel input is not used.
use parking_lot::Mutex;

const CELL: f32 = 128.0;
const GAP: f32 = 8.0;
const PITCH: f32 = CELL + GAP;
const BLOCK_COLS: i32 = 12;
const BLOCK_ROWS: i32 = 8;
const SLOTS: usize = 12;
const FIELD_ASPECT: f32 = 2.2;
const DRAG_THRESHOLD: f32 = 7.0;
const OVERSCAN: f32 = 420.0;
const INERTIA_MIN_SPEED: f32 = 40.0;
const INERTIA_MAX_SPEED: f32 = 4000.0;
const INERTIA_POWER: f32 = 0.3;
const INERTIA_TIME_MS: f32 = 280.0;
const INERTIA_REST: f32 = 0.5;
const STALE_RELEASE_MS: f32 = 80.0;
const VELOCITY_WINDOW_MS: f32 = 48.0;
const SAMPLES: usize = 8;
const FLIP_STAGGER: f32 = 18.0;
const FLIP_MAX_DELAY: f32 = 420.0;
const FLIP_OUT_MS: f32 = 150.0;
const FLIP_IN_MS: f32 = 210.0;
const WAVE_LIFT: f32 = 90.0;
const WAVE_SCALE: f32 = 0.88;
const WAVE_STAGGER: f32 = 30.0;
const WAVE_MAX_DELAY: f32 = 340.0;
const WAVE_OUT_MS: f32 = 280.0;
const WAVE_IN_MS: f32 = 420.0;
const WAVE_EXIT_MS: f32 = WAVE_MAX_DELAY + WAVE_OUT_MS;

const TEMPLATES: [[(i32, i32, i32, i32); SLOTS]; 4] = [
    [
        (0, 0, 3, 2),
        (3, 0, 6, 4),
        (9, 0, 3, 2),
        (0, 2, 3, 2),
        (9, 2, 3, 4),
        (0, 4, 4, 4),
        (4, 4, 2, 2),
        (6, 4, 3, 2),
        (4, 6, 2, 2),
        (6, 6, 2, 2),
        (8, 6, 2, 2),
        (10, 6, 2, 2),
    ],
    [
        (0, 0, 2, 3),
        (2, 0, 2, 3),
        (4, 0, 2, 3),
        (6, 0, 6, 4),
        (0, 3, 6, 2),
        (6, 4, 2, 4),
        (8, 4, 2, 4),
        (10, 4, 2, 2),
        (0, 5, 2, 3),
        (2, 5, 2, 3),
        (4, 5, 2, 3),
        (10, 6, 2, 2),
    ],
    [
        (0, 0, 6, 4),
        (6, 0, 6, 3),
        (6, 3, 4, 3),
        (10, 3, 2, 2),
        (0, 4, 4, 2),
        (4, 4, 2, 2),
        (10, 5, 2, 3),
        (0, 6, 2, 2),
        (2, 6, 2, 2),
        (4, 6, 2, 2),
        (6, 6, 2, 2),
        (8, 6, 2, 2),
    ],
    [
        (0, 0, 2, 2),
        (2, 0, 2, 2),
        (4, 0, 4, 3),
        (8, 0, 2, 4),
        (10, 0, 2, 4),
        (0, 2, 2, 3),
        (2, 2, 2, 2),
        (4, 3, 4, 3),
        (2, 4, 2, 2),
        (8, 4, 4, 4),
        (0, 5, 2, 3),
        (2, 6, 6, 2),
    ],
];

#[derive(Clone, Copy, Debug, uniffi::Enum)]
pub enum PointerEnd {
    None,
    Click { x: f32, y: f32 },
    Drag,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct WallTile {
    pub index: i32,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct WallPose {
    pub visible: bool,
    pub squeeze: f32,
    pub lift: f32,
    pub scale: f32,
    pub alpha: f32,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct PlacedTile {
    pub index: i32,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub visible: bool,
    pub squeeze: f32,
    pub lift: f32,
    pub scale: f32,
    pub alpha: f32,
}

#[derive(Clone, Copy)]
struct Sample {
    at: f64,
    x: f32,
    y: f32,
}

struct Pointer {
    down: bool,
    origin_x: f32,
    origin_y: f32,
    last_x: f32,
    last_y: f32,
    dragged: bool,
    samples: [Option<Sample>; SAMPLES],
    next: usize,
}

impl Pointer {
    fn new() -> Self {
        Self {
            down: false,
            origin_x: 0.0,
            origin_y: 0.0,
            last_x: 0.0,
            last_y: 0.0,
            dragged: false,
            samples: [None; SAMPLES],
            next: 0,
        }
    }

    fn press(&mut self, x: f32, y: f32, now: f64) {
        *self = Self::new();
        self.down = true;
        self.origin_x = x;
        self.origin_y = y;
        self.last_x = x;
        self.last_y = y;
        self.sample(x, y, now);
    }

    fn sample(&mut self, x: f32, y: f32, now: f64) {
        self.samples[self.next] = Some(Sample { at: now, x, y });
        self.next = (self.next + 1) % SAMPLES;
    }

    fn velocity(&self) -> (f32, f32) {
        let Some(newest) = self.samples[(self.next + SAMPLES - 1) % SAMPLES] else { return (0.0, 0.0) };
        let mut oldest = None;
        for step in 2..=SAMPLES {
            let Some(sample) = self.samples[(self.next + SAMPLES - step) % SAMPLES] else { break };
            if newest.at - sample.at > f64::from(VELOCITY_WINDOW_MS) {
                break;
            }
            oldest = Some(sample);
        }
        let Some(oldest) = oldest else { return (0.0, 0.0) };
        let seconds = (newest.at - oldest.at) as f32 / 1000.0;
        if seconds <= 0.0 {
            return (0.0, 0.0);
        }
        ((newest.x - oldest.x) / seconds, (newest.y - oldest.y) / seconds)
    }
}

#[derive(Clone, Copy)]
struct Inertia {
    from_x: f32,
    from_y: f32,
    along_x: f32,
    along_y: f32,
    amplitude: f32,
    started: f64,
}

impl Inertia {
    fn at(&self, now: f64) -> (f32, f32, bool) {
        let remaining = self.amplitude * (-((now - self.started) as f32) / INERTIA_TIME_MS).exp();
        let moving = remaining >= INERTIA_REST;
        let travelled = if moving { self.amplitude - remaining } else { self.amplitude };
        (self.from_x + self.along_x * travelled, self.from_y + self.along_y * travelled, moving)
    }
}

#[derive(Clone, Copy)]
enum ShiftKind {
    Wave,
    Flip { x: f32, y: f32 },
}

#[derive(Clone, Copy)]
struct Shift {
    kind: ShiftKind,
    started: f64,
    scale: f32,
}

struct Board {
    pan_x: f32,
    pan_y: f32,
    view_w: f32,
    view_h: f32,
    pointer: Pointer,
    inertia: Option<Inertia>,
    shift: Option<Shift>,
}

impl Board {
    fn scale(&self) -> f32 {
        wall_scale(self.view_w).max(0.01)
    }
}

/// The infinite cover wall's camera, drag and the tab-change / open-collection motion.
#[derive(uniffi::Object)]
pub struct WallBoard {
    board: Mutex<Board>,
}

impl Default for WallBoard {
    fn default() -> Self {
        Self::new()
    }
}

#[uniffi::export]
impl WallBoard {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            board: Mutex::new(Board {
                pan_x: 0.0,
                pan_y: 0.0,
                view_w: 1.0,
                view_h: 1.0,
                pointer: Pointer::new(),
                inertia: None,
                shift: None,
            }),
        }
    }

    pub fn resize(&self, width: f32, height: f32) {
        let mut board = self.board.lock();
        board.view_w = width.max(1.0);
        board.view_h = height.max(1.0);
    }

    pub fn pointer_down(&self, x: f32, y: f32, now_ms: f64) {
        let mut board = self.board.lock();
        board.inertia = None;
        board.pointer.press(x, y, now_ms);
    }

    /// Moves the camera once the pointer has travelled more than 7px from the press. Returns whether it is dragging.
    pub fn pointer_move(&self, x: f32, y: f32, now_ms: f64) -> bool {
        let mut board = self.board.lock();
        if !board.pointer.down {
            return false;
        }
        board.pointer.sample(x, y, now_ms);
        let scale = board.scale();
        let (origin_x, origin_y, last_x, last_y, dragged) = (
            board.pointer.origin_x,
            board.pointer.origin_y,
            board.pointer.last_x,
            board.pointer.last_y,
            board.pointer.dragged,
        );
        board.pointer.last_x = x;
        board.pointer.last_y = y;
        if dragged {
            board.pan_x += (last_x - x) / scale;
            board.pan_y += (last_y - y) / scale;
            return true;
        }
        let distance = ((x - origin_x).powi(2) + (y - origin_y).powi(2)).sqrt();
        if distance <= DRAG_THRESHOLD {
            return false;
        }
        board.pointer.dragged = true;
        board.pan_x += (origin_x - x) / scale;
        board.pan_y += (origin_y - y) / scale;
        true
    }

    pub fn pointer_up(&self, now_ms: f64) -> PointerEnd {
        let mut board = self.board.lock();
        if !board.pointer.down {
            return PointerEnd::None;
        }
        board.pointer.down = false;
        if !board.pointer.dragged {
            return PointerEnd::Click { x: board.pointer.origin_x, y: board.pointer.origin_y };
        }
        let (vx, vy) = board.pointer.velocity();
        let newest = board.pointer.samples[(board.pointer.next + SAMPLES - 1) % SAMPLES];
        let stale = newest.is_none_or(|sample| now_ms - sample.at > f64::from(STALE_RELEASE_MS));
        let speed = (vx * vx + vy * vy).sqrt();
        if !stale && speed >= INERTIA_MIN_SPEED {
            let scale = board.scale();
            let amplitude = speed.min(INERTIA_MAX_SPEED) * INERTIA_POWER;
            board.inertia = Some(Inertia {
                from_x: board.pan_x,
                from_y: board.pan_y,
                along_x: -vx / speed / scale,
                along_y: -vy / speed / scale,
                amplitude,
                started: now_ms,
            });
        }
        PointerEnd::Drag
    }

    /// Advances the coast. Returns whether the camera is still moving on its own.
    pub fn frame(&self, now_ms: f64) -> bool {
        let mut board = self.board.lock();
        let Some(inertia) = board.inertia else { return false };
        let (x, y, moving) = inertia.at(now_ms);
        board.pan_x = x;
        board.pan_y = y;
        if !moving {
            board.inertia = None;
        }
        moving
    }

    pub fn pan_x(&self) -> f32 {
        self.board.lock().pan_x
    }

    pub fn pan_y(&self) -> f32 {
        self.board.lock().pan_y
    }

    /// Home-tab change: the whole wall lifts away, then the new tiles land.
    pub fn begin_wave(&self, now_ms: f64) {
        let mut board = self.board.lock();
        let scale = board.scale();
        board.shift = Some(Shift { kind: ShiftKind::Wave, started: now_ms, scale });
    }

    /// Opening a collection: tiles turn over, starting at the pressed point.
    pub fn begin_flip(&self, x: f32, y: f32, now_ms: f64) {
        let mut board = self.board.lock();
        let scale = board.scale();
        board.shift = Some(Shift { kind: ShiftKind::Flip { x, y }, started: now_ms, scale });
    }

    pub fn clear_shift(&self) {
        self.board.lock().shift = None;
    }

    pub fn shift_running(&self, now_ms: f64) -> bool {
        let board = self.board.lock();
        board.shift.as_ref().is_some_and(|shift| now_ms - shift.started < f64::from(shift_span(shift)))
    }

    /// One tile of the outgoing (`leaving`) or incoming layer.
    pub fn pose(&self, x: f32, y: f32, width: f32, height: f32, now_ms: f64, leaving: bool) -> WallPose {
        let board = self.board.lock();
        pose_at(&board, x, y, width, height, now_ms, leaving)
    }

    /// Visible tiles and their pose in one lock. Drag draws this once per frame.
    pub fn placed(&self, count: i32, now_ms: f64, leaving: bool) -> Vec<PlacedTile> {
        let board = self.board.lock();
        // Draw only a sliver past the screen. Cover requests still use the 420px overscan.
        visible_tiles(board.pan_x, board.pan_y, board.view_w, board.view_h, count.max(0) as usize, 48.0)
            .into_iter()
            .map(|tile| {
                let pose = pose_at(&board, tile.x, tile.y, tile.width, tile.height, now_ms, leaving);
                PlacedTile {
                    index: tile.index,
                    x: tile.x,
                    y: tile.y,
                    width: tile.width,
                    height: tile.height,
                    visible: pose.visible,
                    squeeze: pose.squeeze,
                    lift: pose.lift,
                    scale: pose.scale,
                    alpha: pose.alpha,
                }
            })
            .collect()
    }

    /// Tiles whose screen rectangles meet the viewport, in the wall's own pixel space (before display density).
    pub fn tiles(&self, count: i32) -> Vec<WallTile> {
        let board = self.board.lock();
        visible_tiles(board.pan_x, board.pan_y, board.view_w, board.view_h, count.max(0) as usize, OVERSCAN)
    }

    /// The item index drawn at a screen point, or -1.
    pub fn hit(&self, x: f32, y: f32, count: i32) -> i32 {
        let board = self.board.lock();
        visible_tiles(board.pan_x, board.pan_y, board.view_w, board.view_h, count.max(0) as usize, OVERSCAN)
            .into_iter()
            .rev()
            .find(|tile| x >= tile.x && y >= tile.y && x < tile.x + tile.width && y < tile.y + tile.height)
            .map(|tile| tile.index)
            .unwrap_or(-1)
    }
}

fn pose_at(board: &Board, x: f32, y: f32, width: f32, height: f32, now_ms: f64, leaving: bool) -> WallPose {
    let Some(shift) = board.shift.as_ref() else {
        return rest();
    };
    let elapsed = (now_ms - shift.started) as f32;
    match shift.kind {
        ShiftKind::Flip { x: origin_x, y: origin_y } => {
            let center_x = x + width / 2.0;
            let center_y = y + height / 2.0;
            let cells = ((center_x - origin_x).powi(2) + (center_y - origin_y).powi(2)).sqrt() / shift.scale / PITCH;
            let delay = (cells * FLIP_STAGGER).min(FLIP_MAX_DELAY);
            flip_pose(elapsed - delay, leaving)
        }
        ShiftKind::Wave => {
            let cells = (x.max(0.0) + y.max(0.0)) / shift.scale / PITCH;
            let stagger = (cells * WAVE_STAGGER).min(WAVE_MAX_DELAY);
            if leaving {
                wave_pose(elapsed - (WAVE_MAX_DELAY - stagger), true, shift.scale)
            } else {
                wave_pose(elapsed - WAVE_EXIT_MS - stagger, false, shift.scale)
            }
        }
    }
}

fn rest() -> WallPose {
    WallPose { visible: true, squeeze: 1.0, lift: 0.0, scale: 1.0, alpha: 1.0 }
}

fn flip_pose(t: f32, leaving: bool) -> WallPose {
    if leaving {
        if t < 0.0 {
            rest()
        } else if t < FLIP_OUT_MS {
            turned(90.0 * ease(0.55, 0.0, 0.9, 0.45, t / FLIP_OUT_MS))
        } else {
            WallPose { visible: false, squeeze: 0.0, lift: 0.0, scale: 1.0, alpha: 1.0 }
        }
    } else if t < 0.0 {
        WallPose { visible: false, squeeze: 0.0, lift: 0.0, scale: 1.0, alpha: 1.0 }
    } else if t < FLIP_IN_MS {
        turned(90.0 * (1.0 - ease(0.2, 0.7, 0.25, 1.0, t / FLIP_IN_MS)))
    } else {
        rest()
    }
}

fn turned(degrees: f32) -> WallPose {
    WallPose { visible: true, squeeze: degrees.to_radians().cos().max(0.0), lift: 0.0, scale: 1.0, alpha: 1.0 }
}

fn wave_pose(t: f32, leaving: bool, scale: f32) -> WallPose {
    if leaving {
        if t < 0.0 {
            return rest();
        }
        if t >= WAVE_OUT_MS {
            return WallPose { visible: false, squeeze: 1.0, lift: WAVE_LIFT * scale, scale: WAVE_SCALE, alpha: 0.0 };
        }
        lifted(ease(0.4, 0.0, 1.0, 1.0, t / WAVE_OUT_MS), scale)
    } else if t < 0.0 {
        WallPose { visible: false, squeeze: 1.0, lift: WAVE_LIFT * scale, scale: WAVE_SCALE, alpha: 0.0 }
    } else if t < WAVE_IN_MS {
        lifted(1.0 - ease(0.2, 0.9, 0.3, 1.04, t / WAVE_IN_MS), scale)
    } else {
        rest()
    }
}

fn lifted(progress: f32, scale: f32) -> WallPose {
    WallPose {
        visible: true,
        squeeze: 1.0,
        lift: WAVE_LIFT * progress * scale,
        scale: 1.0 - (1.0 - WAVE_SCALE) * progress,
        alpha: (1.0 - progress).clamp(0.0, 1.0),
    }
}

fn shift_span(shift: &Shift) -> f32 {
    match shift.kind {
        ShiftKind::Wave => WAVE_EXIT_MS + WAVE_MAX_DELAY + WAVE_IN_MS,
        ShiftKind::Flip { .. } => FLIP_MAX_DELAY + FLIP_OUT_MS + FLIP_IN_MS,
    }
}

/// `getBravaisScale`.
fn wall_scale(viewport_width: f32) -> f32 {
    if viewport_width < 640.0 {
        0.52
    } else if viewport_width < 1100.0 {
        0.64
    } else {
        0.76
    }
}

/// CSS `cubic-bezier(x1, y1, x2, y2)`, the same solver the desktop animator uses.
fn ease(x1: f32, y1: f32, x2: f32, y2: f32, t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    if x1 == y1 && x2 == y2 {
        return t;
    }
    let bezier = |a: f32, b: f32, s: f32| 3.0 * a * s * (1.0 - s).powi(2) + 3.0 * b * s * s * (1.0 - s) + s.powi(3);
    let (mut low, mut high) = (0.0, 1.0);
    for _ in 0..20 {
        let mid = (low + high) / 2.0;
        if bezier(x1, x2, mid) < t {
            low = mid;
        } else {
            high = mid;
        }
    }
    bezier(y1, y2, (low + high) / 2.0)
}

fn visible_tiles(pan_x: f32, pan_y: f32, view_w: f32, view_h: f32, count: usize, margin: f32) -> Vec<WallTile> {
    if count == 0 || view_w <= 0.0 || view_h <= 0.0 {
        return Vec::new();
    }
    let scale = wall_scale(view_w).max(0.01);
    let (columns, rows) = wrap_period(count);
    let start = nearest_slot();
    let offset = (count - start % count) % count;
    let (block_w, block_h) = (BLOCK_COLS as f32 * PITCH, BLOCK_ROWS as f32 * PITCH);
    let half_w = view_w / scale / 2.0 + margin;
    let half_h = view_h / scale / 2.0 + margin;
    let col_from = ((pan_x - half_w) / block_w).floor() as i32 - 1;
    let col_to = ((pan_x + half_w) / block_w).floor() as i32 + 1;
    let row_from = ((pan_y - half_h) / block_h).floor() as i32 - 1;
    let row_to = ((pan_y + half_h) / block_h).floor() as i32 + 1;
    let mut tiles = Vec::new();
    for row in row_from..=row_to {
        for column in col_from..=col_to {
            for (slot, (x, y, cols, rows_cell)) in block_cells(column, row).into_iter().enumerate() {
                let Some(index) = wrap_item(column, row, slot, count, columns, rows, offset) else { continue };
                let world_x = (column * BLOCK_COLS + x) as f32 * PITCH;
                let world_y = (row * BLOCK_ROWS + y) as f32 * PITCH;
                let world_w = cols as f32 * PITCH - GAP;
                let world_h = rows_cell as f32 * PITCH - GAP;
                let screen_x = (world_x - pan_x) * scale + view_w / 2.0;
                let screen_y = (world_y - pan_y) * scale + view_h / 2.0;
                let screen_w = world_w * scale;
                let screen_h = world_h * scale;
                if screen_x + screen_w < -margin
                    || screen_y + screen_h < -margin
                    || screen_x > view_w + margin
                    || screen_y > view_h + margin
                {
                    continue;
                }
                tiles.push(WallTile {
                    index: i32::try_from(index).unwrap_or(i32::MAX),
                    x: screen_x,
                    y: screen_y,
                    width: screen_w,
                    height: screen_h,
                });
            }
        }
    }
    tiles
}

fn wrap_period(count: usize) -> (i32, i32) {
    let blocks = count.max(1).div_ceil(SLOTS) as f32;
    let columns = if blocks <= 1.0 {
        1
    } else {
        ((FIELD_ASPECT * blocks * BLOCK_ROWS as f32 / BLOCK_COLS as f32).sqrt().round() as i32).max(1)
    };
    let rows = (blocks / columns as f32).ceil() as i32;
    (columns, rows.max(1))
}

fn wrap_item(
    column: i32,
    row: i32,
    slot: usize,
    count: usize,
    columns: i32,
    rows: i32,
    offset: usize,
) -> Option<usize> {
    if count == 0 {
        return None;
    }
    let block = (row.rem_euclid(rows) * columns + column.rem_euclid(columns)) as usize;
    Some((block * SLOTS + slot + offset) % count)
}

/// The slot of block (0, 0) whose centre is nearest `(GAP, 0)`, which is where item 0 starts.
fn nearest_slot() -> usize {
    block_cells(0, 0)
        .into_iter()
        .enumerate()
        .min_by(|(_, a), (_, b)| center_distance(*a).total_cmp(&center_distance(*b)))
        .map_or(0, |(slot, _)| slot)
}

fn center_distance(cell: (i32, i32, i32, i32)) -> f32 {
    let (x, y, cols, rows) = cell;
    let cx = x as f32 * PITCH + (cols as f32 * PITCH - GAP) / 2.0;
    let cy = y as f32 * PITCH + (rows as f32 * PITCH - GAP) / 2.0;
    (cx - GAP).hypot(cy)
}

fn block_cells(column: i32, row: i32) -> [(i32, i32, i32, i32); SLOTS] {
    let template = (column + row * 2).rem_euclid(TEMPLATES.len() as i32) as usize;
    let reflection = mix(column, row) % 4;
    TEMPLATES[template].map(|(x, y, cols, rows)| {
        let x = if reflection & 1 == 1 { BLOCK_COLS - x - cols } else { x };
        let y = if reflection & 2 == 2 { BLOCK_ROWS - y - rows } else { y };
        (x, y, cols, rows)
    })
}

/// `mixBlockCoords`, with the same 32-bit multiply the original uses.
fn mix(column: i32, row: i32) -> u32 {
    let mut value = (column.wrapping_mul(0x9e37_79b1_u32 as i32) ^ row.wrapping_mul(0x85eb_ca6b_u32 as i32)) as u32;
    value = ((value ^ (value >> 15)) as i32).wrapping_mul(0x2c1b_3c6d) as u32;
    value ^= value >> 12;
    value
}
