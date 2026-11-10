// The settings the phone reads and writes: language, the home tab, playback, theme and the equalizer.
use folia_core::{AudioQuality, EqualizerSettings, Language, LanguagePreference, Repeat, SOUND_PRESETS};
use folia_i18n::{I18n, I18nError};
use folia_store::{AppearanceSettings, AudioSettings, GeneralSettings, HomeTab, PlaybackSettings, Store, ThemeMode};
use parking_lot::Mutex;

use crate::BridgeError;

pub(crate) struct SettingsHost {
    store: Store,
    general: Mutex<GeneralSettings>,
    playback: Mutex<PlaybackSettings>,
    appearance: Mutex<AppearanceSettings>,
    audio: Mutex<AudioSettings>,
    locale: Mutex<String>,
    i18n: Mutex<I18n>,
}

impl SettingsHost {
    pub(crate) fn open(config: &std::path::Path) -> Result<Self, BridgeError> {
        let store = Store::at(config);
        let general = store.load_or_default::<GeneralSettings>();
        let playback = store.load_or_default::<PlaybackSettings>();
        let appearance = store.load_or_default::<AppearanceSettings>();
        let audio = store.load_or_default::<AudioSettings>();
        let i18n = I18n::new(Language::En).map_err(i18n_err)?;
        Ok(Self {
            store,
            general: Mutex::new(general),
            playback: Mutex::new(playback),
            appearance: Mutex::new(appearance),
            audio: Mutex::new(audio),
            locale: Mutex::new(String::new()),
            i18n: Mutex::new(i18n),
        })
    }

    pub(crate) fn set_system_locale(&self, tag: &str) {
        *self.locale.lock() = tag.to_owned();
        self.rebuild_i18n();
    }

    pub(crate) fn label(&self, key: &str) -> String {
        self.i18n.lock().t(key).to_owned()
    }

    pub(crate) fn language_preference(&self) -> String {
        match self.general.lock().language {
            LanguagePreference::System => "system".to_owned(),
            LanguagePreference::Fixed(language) => language.code().to_owned(),
        }
    }

    pub(crate) fn set_language_preference(&self, value: &str) -> Result<(), BridgeError> {
        self.general.lock().language = if value == "system" {
            LanguagePreference::System
        } else {
            LanguagePreference::Fixed(Language::from_locale(value))
        };
        self.save_general()?;
        self.rebuild_i18n();
        Ok(())
    }

    pub(crate) fn home_tab(&self) -> String {
        match self.general.lock().home_tab {
            HomeTab::Playlist => "playlist",
            HomeTab::Radio => "radio",
            HomeTab::Explore => "explore",
            HomeTab::Albums => "albums",
            HomeTab::Local => "local",
        }
        .to_owned()
    }

    pub(crate) fn set_home_tab(&self, tab: &str) -> Result<(), BridgeError> {
        self.general.lock().home_tab = match tab {
            "radio" => HomeTab::Radio,
            "explore" => HomeTab::Explore,
            "albums" => HomeTab::Albums,
            "local" => HomeTab::Local,
            _ => HomeTab::Playlist,
        };
        self.save_general()
    }

    pub(crate) fn online_provider(&self) -> String {
        self.general.lock().online_provider.clone().unwrap_or_else(|| "netease".to_owned())
    }

    pub(crate) fn set_online_provider(&self, provider: &str) -> Result<(), BridgeError> {
        self.general.lock().online_provider = Some(provider.to_owned());
        self.save_general()
    }

    pub(crate) fn dark_theme(&self) -> bool {
        self.appearance.lock().theme != ThemeMode::Light
    }

    pub(crate) fn set_dark_theme(&self, dark: bool) -> Result<(), BridgeError> {
        self.appearance.lock().theme = if dark { ThemeMode::Dark } else { ThemeMode::Light };
        let appearance = self.appearance.lock().clone();
        self.store.save(&appearance).map_err(store_err)
    }

    pub(crate) fn volume(&self) -> f32 {
        self.playback.lock().volume
    }

    pub(crate) fn repeat(&self) -> Repeat {
        self.playback.lock().repeat
    }

    pub(crate) fn set_playback(&self, volume: f32, repeat: Repeat) -> Result<(), BridgeError> {
        let mut playback = self.playback.lock();
        playback.volume = volume.clamp(0.0, 1.0);
        playback.repeat = repeat;
        let saved = playback.clone();
        drop(playback);
        self.store.save(&saved).map_err(store_err)
    }

    pub(crate) fn audio_quality(&self) -> AudioQuality {
        self.playback.lock().audio_quality
    }

    pub(crate) fn set_audio_quality(&self, id: &str) -> Result<(), BridgeError> {
        let quality = match id {
            "standard" => AudioQuality::Standard,
            "high" => AudioQuality::High,
            "ultra" => AudioQuality::Ultra,
            "lossless" => AudioQuality::Lossless,
            "hires" => AudioQuality::Hires,
            _ => AudioQuality::High,
        };
        self.playback.lock().audio_quality = quality;
        let saved = self.playback.lock().clone();
        self.store.save(&saved).map_err(store_err)
    }

    pub(crate) fn equalizer(&self) -> EqualizerSettings {
        self.audio.lock().equalizer.clone().clamped()
    }

    pub(crate) fn set_equalizer(&self, equalizer: EqualizerSettings) -> Result<(), BridgeError> {
        self.audio.lock().equalizer = equalizer.clamped();
        let saved = self.audio.lock().clone();
        self.store.save(&saved).map_err(store_err)
    }

    pub(crate) fn preset_ids(&self) -> Vec<String> {
        SOUND_PRESETS.iter().map(|preset| preset.id.to_owned()).collect()
    }

    fn save_general(&self) -> Result<(), BridgeError> {
        let general = self.general.lock().clone();
        self.store.save(&general).map_err(store_err)
    }

    fn rebuild_i18n(&self) {
        let tag = self.locale.lock().clone();
        let language = self.general.lock().language.resolve(Some(&tag));
        match I18n::new(language) {
            Ok(i18n) => *self.i18n.lock() = i18n,
            Err(error) => tracing::error!(error = &error as &dyn std::error::Error, "translation reload failed"),
        }
    }
}

fn store_err(error: folia_store::StoreError) -> BridgeError {
    tracing::error!(error = &error as &dyn std::error::Error, "settings write failed");
    BridgeError::Settings { reason: error.to_string() }
}

fn i18n_err(error: I18nError) -> BridgeError {
    tracing::error!(error = &error as &dyn std::error::Error, "translation initialization failed");
    BridgeError::Translation { reason: error.to_string() }
}
