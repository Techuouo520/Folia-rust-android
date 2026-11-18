//! The scrolling lyrics' rows on springs, the pull reaching each line a little later.
//!
//! The track (the column of rows) is moved by the view; this decides where each row sits against
//! its place on the track. Every row is a mass:
//!
//! - the line being sung is held to its place by a stiff spring: it is the hand that pulls;
//! - rows on the leading side of the motion (those the track moves toward) follow their places
//!   closely, so nothing runs ahead of the hand;
//! - each row on the trailing side hangs on one and the same soft spring, through air that slows
//!   it, to where its place was a moment ago: the pull travels down the lines at a finite speed,
//!   so the k-th line behind the sung one feels the track k steps of {@link DELAY_PER_LINE_S} late.
//!
//! So when the track moves, each trailing line starts a moment after the one before it and lags a
//! little more, and when the track stops they glide into their places one after another, outward
//! from the sung line, every one with the same soft landing (a chain of springs tied row to row
//! was tried: the lines next to the sung one landed hard and the far ones ever softer). Every
//! spring is at least critically damped: nothing bounces (the user asked for Apple Music's soft
//! settle). A new move mid-way only changes what pulls: everything keeps its momentum.
//!
//! The rows of one lyric line (wrapped rows, the translation) share their delay: they move as one.
//!
//! Positions are px down the view, absolute (track offset + place + offset); time in s.

/// Simulation step (s): a frame is split into steps of at most this.
const STEP_S: f64 = 1.0 / 720.0;
/// Springs: natural frequency (rad/s) and damping ratio, relative to the row's moving place. The
/// damping acts on the speed against the moving place, which lets a lightly damped spring pass it
/// when the track stops short; well over critical it only follows.
const HEAD_OMEGA: f64 = 46.0;
const HEAD_ZETA: f64 = 2.2;
const LEAD_OMEGA: f64 = 50.0;
const LEAD_ZETA: f64 = 2.5;
/// Every trailing row's spring to its delayed place (rad/s), damped by the air (ratio).
const TRAIL_OMEGA: f64 = 36.0;
const TRAIL_ZETA: f64 = 1.6;
/// Share of that damping that carries the row along with the track rather than the air. With the
/// shown offsets linear (see KNEE_OFFSET_ROWS) it adds no second speed hump; without it the rows
/// stood still at the start while the sung line pulled away (a visible tear).
const TRAIL_FOLLOW_SHARE: f64 = 0.8;
/// How much later each line further from the sung one feels the track (s), up to so many lines.
const DELAY_PER_LINE_S: f64 = 0.020;
/// How much of the carrying damping follows the track's present speed rather than the delayed one.
const TRAIL_CARRY_NOW: f64 = 1.0;
/// Per line beyond the next one: how much less the present speed carries it, and how much its shown
/// lag is scaled back (the longer delay pulls far lines further behind; this keeps the lag the
/// accepted size, the timing and the shape of the motion untouched).
const CARRY_FALLOFF_PER_LINE: f64 = 0.0;
const LAG_FALLOFF_PER_LINE: f64 = 0.0;
/// Each line further from the sung one shows its whole motion this much later again (s), so the
/// lines land one after another more clearly, every one with the speed profile it had (shifting
/// only the offset gave far rows a stop-and-go second speed hump; a longer pull delay landed later
/// too but piled up the lag).
const DISPLAY_DELAY_PER_LINE_S: f64 = 0.0;
/// How fast a row's display delay may change when the sung line moves on (s per s).
const DISPLAY_DELAY_SLEW: f64 = 0.5;
const MAX_DELAY_LINES: i32 = 6;
/// While the user drags the lyrics: everything follows the finger.
const RIGID_OMEGA: f64 = 70.0;
const RIGID_ZETA: f64 = 1.0;
/// Shown offsets are the physics' own up to KNEE row heights, and only past that ease into MAX.
/// (A curve that compresses from zero bends every row's speed: it moved early while its lag grew
/// and dawdled while it shrank, two humps instead of one rise and one easing.)
const KNEE_OFFSET_ROWS: f64 = 1.6;
/// How strongly the shown lag follows the physics' (1.07: the user asked for 7 % more pull, with
/// the timing and the shape of every row's motion left as they were).
const LAG_GAIN: f64 = 1.07;
const MAX_OFFSET_ROWS: f64 = 2.5;
/// Two rows never come closer than this share of their resting gap.
const MIN_GAP_SHARE: f64 = 0.45;
const COLLISION_OMEGA: f64 = 90.0;
/// Track moves larger than this many row heights in one frame are jumps, not motion.
const JUMP_ROWS: f64 = 3.0;
/// Below these the chain is at rest (px, px/s).
const REST_OFFSET: f64 = 0.06;
const REST_SPEED: f64 = 1.2;

#[derive(Clone, Copy, PartialEq)]
enum Role {
    Head,
    Lead,
    Trail,
}

pub struct Chain {
    row_height: f64,
    tops: Vec<f64>,
    sources: Vec<i32>,
    /// Absolute positions and velocities.
    p: Vec<f64>,
    v: Vec<f64>,
    track: f64,
    head: i32,
    rigid: bool,
    /// +1: the track moves up (on to later lines), -1: down; kept while it pauses.
    heading: i32,
    resting: bool,
    roles: Vec<Role>,
    accel: Vec<f64>,
    /// The track's recent places (time s, px), oldest first, for the delayed pull.
    history: std::collections::VecDeque<(f64, f64)>,
    clock: f64,
    /// Every row's offset over the last moments (time s, offsets), for the shifted showing.
    shown_history: std::collections::VecDeque<(f64, Vec<f64>)>,
    /// Each row's current display delay (s), easing toward its line's.
    display_delay: Vec<f64>,
    /// The user's pull strength (Settings → lyrics), times LAG_GAIN; 0 = no pull at all.
    gain: f64,
}

impl Chain {
    pub fn new(row_height: f32) -> Chain {
        Chain {
            row_height: (row_height as f64).max(1.0),
            tops: Vec::new(),
            sources: Vec::new(),
            p: Vec::new(),
            v: Vec::new(),
            track: 0.0,
            head: -1,
            rigid: false,
            heading: 1,
            resting: true,
            roles: Vec::new(),
            accel: Vec::new(),
            history: std::collections::VecDeque::new(),
            clock: 0.0,
            shown_history: std::collections::VecDeque::new(),
            display_delay: Vec::new(),
            gain: 1.0,
        }
    }

    /// The rows' places on the track (px, top to bottom) and which line each belongs to. Offsets
    /// are kept when the rows are the same ones laid out again.
    pub fn set_rows(&mut self, tops: &[f32], sources: &[i32], row_height: f32) {
        let n = tops.len().min(sources.len());
        self.row_height = (row_height as f64).max(1.0);
        let keep = n == self.tops.len() && self.sources[..] == sources[..n];
        let offsets: Vec<f64> = if keep { (0..n).map(|i| self.p[i] - self.track - self.tops[i]).collect() } else { vec![0.0; n] };
        let speeds: Vec<f64> = if keep { self.v.clone() } else { vec![0.0; n] };
        self.tops = tops[..n].iter().map(|&t| t as f64).collect();
        self.sources = sources[..n].to_vec();
        self.p = (0..n).map(|i| self.track + self.tops[i] + offsets[i]).collect();
        self.v = speeds;
        self.roles = vec![Role::Lead; n];
        self.accel = vec![0.0; n];
        if !keep {
            self.shown_history.clear();
            self.display_delay = vec![0.0; n];
        }
        if !keep {
            self.resting = true;
        }
    }

    /// The line being sung (-1: none); `rigid`: the user is dragging the lyrics.
    pub fn set_head(&mut self, head: i32, rigid: bool) {
        if head != self.head || rigid != self.rigid {
            self.head = head;
            self.rigid = rigid;
            self.resting = false;
        }
    }

    /// Every row at its place, still, with the track at `track`.
    pub fn reset(&mut self, track: f32) {
        self.track = track as f64;
        self.history.clear();
        self.shown_history.clear();
        for d in self.display_delay.iter_mut() {
            *d = 0.0;
        }
        for i in 0..self.p.len() {
            self.p[i] = self.track + self.tops[i];
            self.v[i] = 0.0;
        }
        self.resting = true;
    }

    /// How strongly the rows are pulled (1 = as tuned).
    pub fn set_gain(&mut self, gain: f32) {
        self.gain = (gain as f64).clamp(0.0, 3.0);
    }

    #[cfg(test)]
    pub fn is_resting(&self) -> bool {
        self.resting
    }

    /// Moves on by `dt` with the track now at `track`; writes every row's offset from its place
    /// into `out`. True once at rest (all offsets 0).
    pub fn step(&mut self, dt: f32, track: f32, out: &mut [f32]) -> bool {
        let n = self.p.len();
        let track = track as f64;
        let dt = (dt as f64).clamp(0.0, 1.0 / 20.0);
        let moved = track - self.track;
        let mut ran = false;
        if moved.abs() > JUMP_ROWS * self.row_height || dt <= 0.0 {
            // A jump (a seek, a new layout): everything goes with it.
            for i in 0..n {
                self.p[i] += moved;
            }
            for entry in self.history.iter_mut() {
                entry.1 += moved;
            }
            self.track = track;
        } else if moved != 0.0 || !self.resting {
            ran = true;
            self.resting = false;
            let steps = ((dt / STEP_S).ceil() as usize).max(1);
            let h = dt / steps as f64;
            let track_speed = moved / dt;
            if track_speed.abs() > 4.0 {
                self.heading = if track_speed < 0.0 { 1 } else { -1 };
            }
            self.assign_roles();
            let start = self.track;
            for s in 1..=steps {
                let t = start + moved * (s as f64 / steps as f64);
                self.substep(h, t, track_speed);
            }
            self.track = track;
            self.check_rest(track_speed);
        }
        // Record this moment's offsets, then show each row's from its line's delay ago.
        if !ran {
            // At rest the clock still runs, so the shifted showing drains to zero.
            self.clock += dt;
        }
        let now: Vec<f64> = (0..n)
            .map(|i| if self.resting { 0.0 } else { self.p[i] - self.track - self.tops[i] })
            .collect();
        self.shown_history.push_back((self.clock, now));
        let keep = MAX_DELAY_LINES as f64 * DISPLAY_DELAY_PER_LINE_S + 0.1;
        while self.shown_history.len() > 2 && self.shown_history[1].0 < self.clock - keep {
            self.shown_history.pop_front();
        }
        if self.display_delay.len() != n {
            self.display_delay = vec![0.0; n];
        }
        let knee = KNEE_OFFSET_ROWS * self.row_height;
        let room = (MAX_OFFSET_ROWS - KNEE_OFFSET_ROWS) * self.row_height;
        let mut any_shown = false;
        for i in 0..n.min(out.len()) {
            let lines = if self.roles.get(i) == Some(&Role::Trail) && self.head >= 0 {
                (self.sources[i] - self.head).abs().clamp(1, MAX_DELAY_LINES)
            } else {
                0
            };
            let want = lines as f64 * DISPLAY_DELAY_PER_LINE_S;
            let slew = DISPLAY_DELAY_SLEW * dt;
            self.display_delay[i] += (want - self.display_delay[i]).clamp(-slew, slew);
            // The row's whole motion, shifted: where it was then (its offset then, plus how far the
            // track has come since), so its speed keeps the one rise and one easing it had.
            let then = self.clock - self.display_delay[i];
            let lag_scale = LAG_GAIN * self.gain / (1.0 + LAG_FALLOFF_PER_LINE * (lines - 1).max(0) as f64);
            let u = (self.shown_at(i, then) + self.track_at(then) - self.track) * lag_scale;
            if u.abs() > 0.05 {
                any_shown = true;
            }
            let shown = if u.abs() <= knee { u } else { u.signum() * (knee + room * ((u.abs() - knee) / room).tanh()) };
            out[i] = shown as f32;
        }
        if self.resting && !any_shown {
            for o in out.iter_mut().take(n) {
                *o = 0.0;
            }
            return true;
        }
        false
    }

    /// Row `i`'s offset at time `t`, from the recorded moments.
    fn shown_at(&self, i: usize, t: f64) -> f64 {
        let h = &self.shown_history;
        let Some(last) = h.back() else { return 0.0 };
        if t >= last.0 {
            return last.1.get(i).copied().unwrap_or(0.0);
        }
        let k = h.partition_point(|e| e.0 < t);
        if k == 0 {
            return h[0].1.get(i).copied().unwrap_or(0.0);
        }
        let (t0, ref a) = h[k - 1];
        let (t1, ref b) = h[k];
        let f = if t1 > t0 { (t - t0) / (t1 - t0) } else { 1.0 };
        let a = a.get(i).copied().unwrap_or(0.0);
        let b = b.get(i).copied().unwrap_or(0.0);
        a + (b - a) * f
    }

    fn assign_roles(&mut self) {
        for i in 0..self.p.len() {
            let source = self.sources[i];
            self.roles[i] = if self.rigid || self.head < 0 {
                Role::Lead
            } else if source == self.head {
                Role::Head
            } else if (self.heading > 0) == (source > self.head) {
                Role::Trail
            } else {
                Role::Lead
            };
        }
    }

    fn substep(&mut self, h: f64, track: f64, track_speed: f64) {
        let n = self.p.len();
        self.clock += h;
        self.history.push_back((self.clock, track));
        let keep = MAX_DELAY_LINES as f64 * (DELAY_PER_LINE_S + DISPLAY_DELAY_PER_LINE_S) + 0.05;
        while self.history.len() > 2 && self.history[1].0 < self.clock - keep {
            self.history.pop_front();
        }
        for i in 0..n {
            let u = self.p[i] - track - self.tops[i];
            let w = self.v[i] - track_speed;
            let a = if !self.rigid && self.roles[i] == Role::Trail {
                // Where its place was when the pull left the sung line.
                let lines = (self.sources[i] - self.head).abs().clamp(1, MAX_DELAY_LINES);
                let at = self.clock - lines as f64 * DELAY_PER_LINE_S;
                let then = self.track_at(at);
                let then_speed = (then - self.track_at(at - h)) / h;
                let target = then + self.tops[i];
                // Part of the damping is against the place's own speed (less lag while it moves),
                // the rest is air against the row's speed: too much of the first and the row would
                // pass its place when the track stops short.
                let c = 2.0 * TRAIL_ZETA * TRAIL_OMEGA;
                // The damping that carries the row along goes by the track's speed NOW: the row
                // starts with the sung line instead of standing still while the line pulls away
                // (its delayed place has not moved yet), and lag builds gently from the spring.
                // Farther lines are carried a little less by the track's present speed, and more by
                // the delayed one: they land later, in one easing (fully carried, a far line rode
                // with the track and then hurried after its delayed place: two speed humps).
                let carry_now = (TRAIL_CARRY_NOW - CARRY_FALLOFF_PER_LINE * (lines - 1) as f64).clamp(0.0, 1.0);
                let carry = carry_now * track_speed + (1.0 - carry_now) * then_speed;
                -TRAIL_OMEGA * TRAIL_OMEGA * (self.p[i] - target)
                    - c * TRAIL_FOLLOW_SHARE * (self.v[i] - carry)
                    - c * (1.0 - TRAIL_FOLLOW_SHARE) * self.v[i]
            } else {
                let (omega, zeta) = if self.rigid {
                    (RIGID_OMEGA, RIGID_ZETA)
                } else if self.roles[i] == Role::Head {
                    (HEAD_OMEGA, HEAD_ZETA)
                } else {
                    (LEAD_OMEGA, LEAD_ZETA)
                };
                -omega * omega * u - 2.0 * zeta * omega * w
            };
            self.accel[i] = a;
        }
        // Rows never pass through each other.
        for i in 0..n.saturating_sub(1) {
            let rest_gap = self.tops[i + 1] - self.tops[i];
            let gap = self.p[i + 1] - self.p[i];
            let min_gap = rest_gap * MIN_GAP_SHARE;
            if gap < min_gap {
                let push = COLLISION_OMEGA * COLLISION_OMEGA * (min_gap - gap) * 0.5;
                self.accel[i] -= push;
                self.accel[i + 1] += push;
            }
        }
        for i in 0..n {
            self.v[i] += self.accel[i] * h;
            self.p[i] += self.v[i] * h;
        }
    }

    /// The track's place at time `t` (the oldest known before then, the newest after).
    fn track_at(&self, t: f64) -> f64 {
        let Some(&(first_t, first)) = self.history.front() else { return self.track };
        if t <= first_t {
            return first;
        }
        let k = self.history.partition_point(|e| e.0 < t);
        if k >= self.history.len() {
            return self.history.back().map(|e| e.1).unwrap_or(self.track);
        }
        let (t1, y1) = self.history[k];
        let (t0, y0) = self.history[k - 1];
        let f = if t1 > t0 { (t - t0) / (t1 - t0) } else { 1.0 };
        y0 + (y1 - y0) * f
    }

    fn check_rest(&mut self, track_speed: f64) {
        if track_speed.abs() > REST_SPEED {
            return;
        }
        for i in 0..self.p.len() {
            let u = self.p[i] - self.track - self.tops[i];
            if u.abs() > REST_OFFSET || (self.v[i] - track_speed).abs() > REST_SPEED {
                return;
            }
        }
        for i in 0..self.p.len() {
            self.p[i] = self.track + self.tops[i];
            self.v[i] = 0.0;
        }
        self.resting = true;
    }

}

#[cfg(test)]
mod tests {
    use super::*;

    /// 14 lines, one row each, 210 px apart (a 105 px row and the gap after it).
    fn chain() -> Chain {
        let tops: Vec<f32> = (0..14).map(|i| 300.0 + i as f32 * 210.0).collect();
        let sources: Vec<i32> = (0..14).collect();
        let mut c = Chain::new(105.0);
        c.set_rows(&tops, &sources, 105.0);
        c.reset(0.0);
        c
    }

    fn ease(t: f64) -> f64 {
        let t = t.clamp(0.0, 1.0);
        t * t * (3.0 - 2.0 * t)
    }

    #[test]
    fn trailing_rows_lag_more_the_farther_they_are() {
        let mut c = chain();
        c.set_head(3, false);
        let mut out = vec![0f32; 14];
        let mut peak = vec![0f64; 14];
        for f in 0..300 {
            let t = f as f64 / 120.0;
            let track = -210.0 * ease(t / 0.8) - 210.0 * 2.0;
            c.step(1.0 / 120.0, track as f32, &mut out);
            if f == 0 {
                c.reset(track as f32);
            }
            for i in 0..14 {
                peak[i] = peak[i].max(out[i] as f64);
            }
        }
        println!("peak lag: {:?}", &peak.iter().map(|p| p.round()).collect::<Vec<_>>());
        assert!(peak[4] > 3.0, "the next line lags");
        assert!(peak[7] > peak[4], "farther lags more");
        assert!(peak[2].abs() < peak[4], "the leading side stays close");
        assert!(c.is_resting(), "settles");
    }

    #[test]
    fn rapid_moves_stay_bounded_and_settle() {
        let mut c = chain();
        let mut out = vec![0f32; 14];
        let mut track = 0.0f64;
        for f in 0..900 {
            if f % 40 == 0 {
                c.set_head(((f / 40) % 10) as i32, f % 200 == 0);
            }
            track -= if (f / 40) % 2 == 0 { 3.0 } else { -2.0 };
            c.step(1.0 / 120.0, track as f32, &mut out);
            for o in &out {
                assert!(o.is_finite() && o.abs() <= 2.5 * 105.0 + 0.01);
            }
        }
        let mut frames = 0;
        while !c.step(1.0 / 120.0, track as f32, &mut out) {
            frames += 1;
            assert!(frames < 600, "never settles");
        }
    }
}
