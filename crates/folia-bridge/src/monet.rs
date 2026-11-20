//! Android presentation endpoint for Folia's original Monet canvas. One TextureView worker
//! owns the surface, renderer, glyph caches and input queue for its entire lifetime.
use crate::{BridgeError, FoliaBridge, LibraryTrack, covers::CoverLookup};
use folia_core::{AudioFrame, Lyrics, Rgb, Theme};
use folia_visualizer::{Scene, SceneState, Stage, Style, Values, styles::monet};
use iced_core::{Font, Pixels, Point, Rectangle, Renderer as _, Size, mouse};
use iced_wgpu::graphics::{Shell, Viewport, geometry::Renderer as _};
use iced_widget::{
    canvas::{Event, Program},
    image::Handle,
};
use jni::{EnvUnowned, objects::JObject};
use ndk::native_window::NativeWindow;
use raw_window_handle::{DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle, WindowHandle};
use std::{
    cell::RefCell,
    sync::{Arc, Once},
    time::{Duration, Instant},
};

pub(crate) struct Input {
    pub token: u64,
    pub track: LibraryTrack,
    pub lyrics: Option<Arc<Lyrics>>,
    pub lyrics_failed: bool,
    pub position: f64,
    pub clock: Option<folia_player::PlaybackClock>,
}

#[derive(uniffi::Record)]
pub struct MonetFilteredMask {
    pub width: u32,
    pub height: u32,
    pub left: i32,
    pub top: i32,
    pub pixels: Vec<u8>,
}

#[uniffi::export(callback_interface)]
pub trait MonetMaskFilter: Send + Sync {
    fn blur(&self, pixels: Vec<u8>, width: u32, height: u32, radius: f32) -> Option<MonetFilteredMask>;
}

/// FFT work follows demand from the visible spectrum. Neither the renderer nor the audio
/// callback waits for a transform; the playback command thread only publishes song changes.
struct Spectrum {
    jobs: std::sync::mpsc::SyncSender<(u64, folia_player::PlaybackClock)>,
    results: std::sync::mpsc::Receiver<(u64, AudioFrame)>,
    last: Option<(u64, Instant)>,
    frame: AudioFrame,
}
impl Spectrum {
    fn new() -> std::io::Result<Self> {
        let (jobs, requests) = std::sync::mpsc::sync_channel::<(u64, folia_player::PlaybackClock)>(1);
        let (replies, results) = std::sync::mpsc::channel();
        std::thread::Builder::new().name("Monet spectrum".into()).spawn(move || {
            let start = Instant::now();
            while let Ok((token, clock)) = requests.recv() {
                if replies.send((token, clock.spectrum(start.elapsed().as_secs_f64() * 1000.))).is_err() {
                    break;
                }
            }
        })?;
        Ok(Self { jobs, results, last: None, frame: AudioFrame::SILENT })
    }

    fn sample(&mut self, input: &Input) -> AudioFrame {
        let Some(clock) = &input.clock else { return AudioFrame::SILENT };
        if self.last.is_none_or(|(token, _)| token != input.token) {
            self.frame = AudioFrame::SILENT;
        }
        for (token, frame) in self.results.try_iter() {
            if token == input.token {
                self.frame = frame;
            }
        }
        if self.last.is_none_or(|(token, at)| token != input.token || at.elapsed() >= Duration::from_millis(16))
            && self.jobs.try_send((input.token, clock.clone())).is_ok()
        {
            self.last = Some((input.token, Instant::now()));
        }
        self.frame
    }
}

struct Window(NativeWindow);
impl HasWindowHandle for Window {
    fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
        self.0.window_handle()
    }
}
impl HasDisplayHandle for Window {
    fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
        Ok(DisplayHandle::android())
    }
}

#[derive(uniffi::Record)]
pub struct MonetOptions {
    pub font_scale: f32,
    pub keyword_coloring: bool,
    pub show_description: bool,
    pub square_portrait: bool,
    pub show_hanger: bool,
    pub show_spectrum: bool,
    pub line_spectrum: bool,
    pub use_custom: bool,
    pub portrait_path: String,
    pub portrait_offset: f32,
}

/// Immutable input for SalamPlayer's row builder. Offsets count Unicode code points.
#[derive(uniffi::Record)]
pub struct MonetTickerLine {
    pub text: String,
    pub translation: Option<String>,
    pub start_ms: i64,
    pub end_ms: i64,
    pub word_times: Vec<i64>,
    pub word_ends: Vec<i32>,
}

fn ticker_line(line: &folia_core::Line) -> MonetTickerLine {
    let ms = |seconds: f64| (seconds * 1000.).round() as i64;
    let mut bytes = 0;
    let mut points = 0;
    let mut times = Vec::with_capacity(line.words.len());
    let mut ends = Vec::with_capacity(line.words.len());
    for word in &line.words {
        if word.text.is_empty() { continue; }
        let remaining = &line.text[bytes..];
        let Some(at) = remaining.find(&word.text) else { continue };
        let end = at + word.text.len();
        points += remaining[..end].chars().count() as i32;
        bytes += end;
        times.push(ms(word.start));
        ends.push(points);
    }
    if times.is_empty() && !line.text.is_empty() {
        times.push(ms(line.start));
        ends.push(line.text.chars().count() as i32);
    }
    MonetTickerLine {
        text: line.text.clone(), translation: line.translation.clone(), start_ms: ms(line.start),
        end_ms: ms(line.words.last().map_or(line.end, |word| word.end)), word_times: times, word_ends: ends,
    }
}

struct Poster {
    surface: wgpu::Surface<'static>,
    config: wgpu::SurfaceConfiguration,
    session: Option<(Arc<FoliaBridge>, i64, String, String)>,
    filter: Option<monet::MaskFilter>,
    spectrum: Spectrum,
    device: wgpu::Device,
    renderer: iced_wgpu::Renderer,
    state: SceneState,
    token: Option<u64>,
    ticker_lyrics: Option<Arc<Lyrics>>,
    ticker_bounds: Option<[f32; 6]>,
    tuning: Values,
    portrait: Option<Handle>,
    custom: Option<(String, Handle)>,
    pending_offset: Option<f32>,
    mobile_insets: [f32; 4],
    retry_cover: Option<Instant>,
    presented: Option<(u64, usize, u64, u32, u32, u32, bool)>,
    events: Vec<(Event, Point)>,
}
thread_local! { static POSTER: RefCell<Option<Poster>> = const { RefCell::new(None) }; }

impl Poster {
    async fn new(window: Window) -> Result<Self, Box<dyn std::error::Error>> {
        let instance = wgpu::Instance::new(&Default::default());
        let surface = instance.create_surface(window)?;
        let adapter = instance
            .request_adapter(&wgpu::RequestAdapterOptions { compatible_surface: Some(&surface), ..Default::default() })
            .await?;
        let (device, queue) = adapter
            .request_device(&wgpu::DeviceDescriptor {
                required_limits: wgpu::Limits::downlevel_defaults().using_resolution(adapter.limits()),
                ..Default::default()
            })
            .await?;
        let mut config = surface.get_default_config(&adapter, 1, 1).ok_or("missing surface format")?;
        if let Some(format) = surface.get_capabilities(&adapter).formats.into_iter().find(|f| !f.is_srgb()) {
            config.format = format;
        }
        surface.configure(&device, &config);
        static FONTS: Once = Once::new();
        FONTS.call_once(|| {
            if let Ok(mut system) = iced_wgpu::graphics::text::font_system().write() {
                let db = system.raw().db_mut();
                db.load_fonts_dir("/system/fonts");
                db.load_fonts_dir("/product/fonts");
                db.set_sans_serif_family("Roboto");
            }
        });
        let engine = iced_wgpu::Engine::new(&adapter, device.clone(), queue, config.format, None, Shell::headless());
        Ok(Self {
            surface,
            config,
            session: None,
            filter: None,
            spectrum: Spectrum::new()?,
            device,
            renderer: iced_wgpu::Renderer::new(engine, Font::DEFAULT, Pixels(16.)),
            state: SceneState::default(),
            token: None,
            ticker_lyrics: None,
            ticker_bounds: None,
            tuning: Values::default(),
            portrait: None,
            custom: None,
            pending_offset: None,
            mobile_insets: [0.; 4],
            retry_cover: None,
            presented: None,
            events: Vec::new(),
        })
    }

    fn frame(&mut self, app: &FoliaBridge, input: &Input, viewport: Viewport, dark: bool) -> Result<i32, BridgeError> {
        if self.token != Some(input.token) {
            self.token = Some(input.token);
            self.state = SceneState::default();
            self.portrait = None;
            self.retry_cover = Some(Instant::now());
            self.events.clear();
        }
        let Some(lyrics) = &input.lyrics else {
            self.events.clear();
            return Ok(if input.lyrics_failed { 5 } else { 0 });
        };
        let size = viewport.logical_size();
        let track = &input.track;
        let time = input.clock.as_ref().map_or(input.position, |clock| clock.position());
        if self.custom.is_none() && size.width >= 768. && self.retry_cover.is_some_and(|retry| retry <= Instant::now())
        {
            match app.covers.playback(track.path.clone(), track.cover_url.clone()) {
                CoverLookup::Ready { bytes } => {
                    self.portrait = Some(Handle::from_bytes(bytes));
                    self.presented = None;
                    self.retry_cover = None;
                }
                CoverLookup::Missing => self.retry_cover = None,
                CoverLookup::Pending => self.retry_cover = Some(Instant::now() + Duration::from_millis(250)),
            }
        }
        let theme = if dark { Theme::MIDNIGHT } else { Theme { background: Rgb::hex(0xf9f7f3), ..Theme::DAYLIGHT } };
        let appearance = folia_visualizer::MonetAppearance { shell_capsule: true, native_ticker: true, mobile_insets: self.mobile_insets, ..Default::default() };
        let spectrum = self.tuning.flag(monet::manifest(), "showAudioVisualization");
        let physical = viewport.physical_size();
        let key = (
            input.token,
            Arc::as_ptr(lyrics) as usize,
            time.to_bits(),
            physical.width,
            physical.height,
            viewport.scale_factor().to_bits(),
            dark,
        );
        let status = if lyrics.lines.is_empty() { 2 } else { 1 };
        if !spectrum
            && self.presented == Some(key)
            && self.events.is_empty()
            && self.pending_offset.is_none()
            && !self.state.monet_needs_frame()
        {
            return Ok(status);
        }
        let audio = if spectrum { self.spectrum.sample(input) } else { AudioFrame::SILENT };
        let stage = Stage::<monet::Event>::new(Scene {
            monet: &appearance,
            style: Style::Monet,
            time,
            lyrics: &lyrics,
            theme: &theme,
            audio: &audio,
            title: std::borrow::Cow::Borrowed(&track.title),
            artist: std::borrow::Cow::Borrowed(&track.artist),
            album: std::borrow::Cow::Borrowed(&track.album),
            seed: &track.online_id,
            font_scale: 1.,
            stage: None,
            preview: false,
            backdrop: false,
            portrait: self.custom.as_ref().map(|(_, h)| h.clone()).or_else(|| self.portrait.clone()),
            tuning: std::borrow::Cow::Borrowed(&self.tuning),
        });
        let bounds = Rectangle::with_size(size);
        let tick = Event::Window(iced_core::window::Event::RedrawRequested(Instant::now().into()));
        stage.update(&mut self.state, &tick, bounds, mouse::Cursor::Unavailable);
        for (event, at) in self.events.drain(..) {
            if let Some(msg) = stage
                .update(&mut self.state, &event, bounds, mouse::Cursor::Available(at))
                .and_then(|a| a.into_inner().0)
            {
                match msg {
                    monet::Event::Seek(time) => app.playback.seek(time)?,
                    monet::Event::Portrait(offset) => {
                        self.pending_offset = Some(offset.0);
                    }
                }
            }
        }
        let physical = viewport.physical_size();
        if (physical.width, physical.height) != (self.config.width, self.config.height) {
            self.config.width = physical.width;
            self.config.height = physical.height;
            self.surface.configure(&self.device, &self.config);
        }
        let output = match self.surface.get_current_texture() {
            Ok(frame) => frame,
            Err(wgpu::SurfaceError::Outdated | wgpu::SurfaceError::Lost) => {
                self.surface.configure(&self.device, &self.config);
                return Ok(4);
            }
            Err(wgpu::SurfaceError::Timeout) => return Ok(4),
            Err(error) => return Err(BridgeError::Playback { reason: error.to_string() }),
        };
        self.renderer.reset(bounds);
        if let Some(filter) = &self.filter {
            self.state.begin_monet_rail(viewport.scale_factor(), filter.clone());
        }
        for geometry in
            stage.draw(&self.state, &self.renderer, &iced_core::Theme::Light, bounds, mouse::Cursor::Unavailable)
        {
            self.renderer.draw_geometry(geometry);
        }
        if let Some(rail) = self.state.take_monet_rail() {
            iced_wgpu::primitive::Renderer::draw_primitive(&mut self.renderer, bounds, rail);
        }
        // The shared now-playing atmosphere composites grain above the Monet canvas.
        let atmosphere = folia_render::Program::<()>::new(folia_render::Atmosphere {
            time: time as f32,
            grain: 0.04,
            vignette: 0.,
            glow_center: [0.; 2],
            glow_radius: [0.; 2],
            glow_rgb: [0.; 3],
            glow_alpha: 0.,
        });
        let pass = iced_widget::shader::Program::draw(&atmosphere, &(), mouse::Cursor::Unavailable, bounds);
        iced_wgpu::primitive::Renderer::draw_primitive(&mut self.renderer, bounds, pass);
        self.renderer.present(
            Some(folia_visualizer::color(theme.background, 1.)),
            self.config.format,
            &output.texture.create_view(&Default::default()),
            &viewport,
        );
        output.present();
        self.presented = Some(key);
        if let Some(x) = self.pending_offset {
            self.tuning.set_number(monet::manifest(), "portraitOffsetX", x);
        }
        // Low byte is the frame status. The upper bytes publish a completed hanger drag once.
        let offset = self.pending_offset.take().map_or(0, |x| (1 + (-x).round().clamp(0., 150.) as i32) << 8);
        let mut changes = 0;
        if self.ticker_lyrics.as_ref().is_none_or(|last| !Arc::ptr_eq(last, lyrics)) {
            self.ticker_lyrics = Some(Arc::clone(lyrics));
            changes |= 16;
        }
        let bounds = self.state.monet_ticker_bounds().map(|b| b.map(|v| v * viewport.scale_factor()));
        if self.ticker_bounds != bounds {
            self.ticker_bounds = bounds;
            changes |= 32;
        }
        Ok(offset | changes | status)
    }
}

#[uniffi::export]
impl FoliaBridge {
    pub fn monet_landscape_layout(&self, width: f32, height: f32, left: f32, top: f32, right: f32, bottom: f32) -> Vec<f32> {
        folia_visualizer::mobile_layout::landscape(width, height, [left,top,right,bottom], 1.).packed()
    }
    pub fn monet_ticker_lines(&self) -> Vec<MonetTickerLine> {
        POSTER.with_borrow(|p| p.as_ref().and_then(|p| p.ticker_lyrics.as_ref())
            .map_or_else(Vec::new, |song| song.lines.iter().map(ticker_line).collect()))
    }

    pub fn monet_ticker_bounds(&self) -> Vec<f32> {
        POSTER.with_borrow(|p| p.as_ref().and_then(|p| p.ticker_bounds).map_or_else(Vec::new, |b| b.to_vec()))
    }

    pub fn install_monet_mask_filter(&self, filter: Box<dyn MonetMaskFilter>) {
        POSTER.with_borrow_mut(|p| {
            if let Some(p) = p {
                p.presented = None;
                p.filter = Some(Arc::new(move |pixels, w, h, radius| {
                    let mask = filter.blur(pixels, w, h, radius)?;
                    Some(monet::FilteredMask {
                        pixels: mask.pixels,
                        width: mask.width,
                        height: mask.height,
                        left: mask.left,
                        top: mask.top,
                    })
                }));
            }
        });
    }

    pub fn configure_monet(&self, options: MonetOptions) {
        POSTER.with_borrow_mut(|p| {
            let Some(p) = p else { return };
            p.presented = None;
            let m = monet::manifest();
            p.tuning.set_number(m, "fontScale", options.font_scale);
            p.tuning.set_number(m, "portraitOffsetX", options.portrait_offset);
            for (key, value) in [
                ("keywordColoringEnabled", options.keyword_coloring),
                ("showDescription", options.show_description),
                ("showPortraitDragHanger", options.show_hanger),
                ("showAudioVisualization", options.show_spectrum),
            ] {
                p.tuning.set_flag(m, key, value);
            }
            p.tuning.set_choice(m, "audioStyle", if options.line_spectrum { "line" } else { "bar" });
            p.tuning.set_choice(m, "portraitStyle", if options.square_portrait { "square" } else { "rectangular" });
            let path = options.portrait_path;
            p.tuning.set_path(m, "portraitPath", (!path.is_empty()).then_some(path.as_str()));
            let custom = options.use_custom && !path.is_empty();
            p.tuning.set_choice(m, "portraitSource", if custom { "custom" } else { "cover" });
            if !custom {
                p.custom = None;
            } else if p.custom.as_ref().is_none_or(|(current, _)| current != &path) {
                let image = Handle::from_path(&path);
                p.custom = Some((path, image));
            }
        });
    }

    /// Bind string identities once on the owning render thread. Vsync uses a primitive JNI call.
    pub fn select_monet(self: Arc<Self>, track_id: i64, provider: String, online_id: String) {
        self.playback.request_lyrics(track_id, provider.clone(), online_id.clone(), false);
        POSTER.with_borrow_mut(|poster| {
            if let Some(p) = poster {
                p.ticker_lyrics = None;
                p.ticker_bounds = None;
                p.presented = None;
                p.session = Some((self, track_id, provider, online_id));
            }
        });
    }

    pub fn retry_monet_lyrics(&self, track_id: i64, provider: String, online_id: String) {
        self.playback.request_lyrics(track_id, provider, online_id, true);
    }
}

impl Poster {
    fn tick(&mut self, width: i32, height: i32, density: f32, dark: bool) -> Result<i32, BridgeError> {
        let viewport =
            Viewport::with_physical_size(Size::new(width.max(1) as u32, height.max(1) as u32), density.max(0.1));
        let Some((app, track_id, provider, online_id)) = &self.session else { return Ok(0) };
        let Some(input) = app.playback.monet_input() else { return Ok(0) };
        if input.track.id != *track_id || input.track.provider != *provider || input.track.online_id != *online_id {
            return Ok(0);
        }
        let app = app.clone();
        self.frame(&app, &input, viewport, dark)
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_techuouo520_folia_MonetView_drawFrame(
    _: EnvUnowned,
    _: JObject,
    width: i32,
    height: i32,
    density: f32,
    dark: u8,
    left: f32, top: f32, right: f32, bottom: f32,
) -> i32 {
    POSTER.with_borrow_mut(|p| {
        p.as_mut().map_or(3, |p| {
            let insets = [left, top, right, bottom];
            if p.mobile_insets != insets { p.mobile_insets = insets; p.presented = None; }
            p.tick(width, height, density, dark != 0).unwrap_or_else(|error| {
                tracing::error!(%error, "Monet frame");
                3
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_techuouo520_folia_MonetView_attach(
    env: EnvUnowned,
    _: JObject,
    surface: JObject,
) -> u8 {
    // ANativeWindow_fromSurface takes its own reference, released by Window after the wgpu surface.
    let Some(window) = (unsafe { NativeWindow::from_surface(env.as_raw().cast(), surface.as_raw().cast()) }) else {
        return 0;
    };
    let poster = tokio::runtime::Builder::new_current_thread()
        .build()
        .ok()
        .and_then(|rt| rt.block_on(Poster::new(Window(window))).map_err(|e| tracing::error!(%e, "Monet surface")).ok());
    let ready = poster.is_some();
    POSTER.set(poster);
    ready.into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_techuouo520_folia_MonetView_detach(_: EnvUnowned, _: JObject) {
    POSTER.set(None);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_techuouo520_folia_MonetView_pointer(
    _: EnvUnowned,
    _: JObject,
    kind: i32,
    x: f32,
    y: f32,
    delta: f32,
) {
    let at = Point::new(x, y);
    let event = match kind {
        0 => mouse::Event::ButtonPressed(mouse::Button::Left),
        1 => mouse::Event::ButtonReleased(mouse::Button::Left),
        2 => mouse::Event::CursorMoved { position: at },
        3 => mouse::Event::WheelScrolled { delta: mouse::ScrollDelta::Pixels { x: 0., y: delta } },
        _ => return,
    };
    POSTER.with_borrow_mut(|p| {
        if let Some(p) = p {
            p.events.push((Event::Mouse(event), at));
        }
    });
}
