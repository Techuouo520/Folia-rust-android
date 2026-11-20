// Phrase and whole-ASCII-word colour ranges, matching the visualizers' shared wordColoring rules.

use folia_core::{Line, Rgb};
use iced_core::Font;
use std::ops::Range;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct WordColor {
    pub word: String,
    pub color: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WordColorRange {
    pub range: Range<usize>,
    pub color: Rgb,
}

/// Selects longer phrases before overlapping short ones and returns byte ranges suitable for Rust text slices.
pub fn word_color_ranges(text: &str, colors: &[WordColor]) -> Vec<WordColorRange> {
    let mut found: Vec<(usize, WordColorRange)> = Vec::new();
    for entry in colors {
        let target = entry.word.trim();
        let Some(color) = parse_color(&entry.color) else { continue };
        if target.is_empty() {
            continue;
        }
        let cjk = target
            .chars()
            .any(|c| matches!(c, '\u{4e00}'..='\u{9fa5}' | '\u{3040}'..='\u{30ff}' | '\u{ac00}'..='\u{d7af}'));
        if cjk {
            for (start, _) in text.match_indices(target) {
                found.push((
                    target.encode_utf16().count(),
                    WordColorRange { range: start..start + target.len(), color },
                ));
            }
        } else {
            let words: Vec<String> = target
                .split_whitespace()
                .map(|word| {
                    word.chars()
                        .filter(|c| c.is_ascii_alphanumeric() || *c == '_')
                        .flat_map(char::to_lowercase)
                        .collect()
                })
                .filter(|word: &String| !word.is_empty())
                .collect();
            let priority = words.iter().map(String::len).max().unwrap_or(0);
            let mut start = None;
            for (at, c) in text.char_indices().chain(std::iter::once((text.len(), ' '))) {
                if c.is_ascii_alphanumeric() || c == '_' {
                    start.get_or_insert(at);
                } else if let Some(begin) = start.take()
                    && words.iter().any(|word| text[begin..at].eq_ignore_ascii_case(word))
                {
                    found.push((priority, WordColorRange { range: begin..at, color }));
                }
            }
        }
    }
    found.sort_by(|(pa, a), (pb, b)| {
        pb.cmp(pa).then(a.range.start.cmp(&b.range.start)).then(a.range.end.cmp(&b.range.end))
    });
    let mut selected: Vec<WordColorRange> = Vec::new();
    for (_, range) in found {
        if selected.iter().all(|held| held.range.end <= range.range.start || range.range.end <= held.range.start) {
            selected.push(range);
        }
    }
    selected.sort_by_key(|range| range.range.start);
    selected
}

pub fn parse_color(text: &str) -> Option<Rgb> {
    let text = text.trim();
    if let Some(hex) = text.strip_prefix('#') {
        if hex.len() == 6 {
            return u32::from_str_radix(hex, 16).ok().map(Rgb::hex);
        }
        if hex.len() == 3 {
            let value = u16::from_str_radix(hex, 16).ok()?;
            return Some(Rgb {
                r: ((value >> 8) as u8) * 17,
                g: ((value >> 4 & 15) as u8) * 17,
                b: ((value & 15) as u8) * 17,
            });
        }
    }
    let rgb = text.strip_prefix("rgb(").or_else(|| text.strip_prefix("rgba("))?.strip_suffix(')')?;
    let mut channels = rgb
        .split(',')
        .take(3)
        .map(|v| v.trim().parse::<f32>().ok().filter(|v| v.is_finite()).map(|v| v.clamp(0.0, 255.0).round() as u8));
    Some(Rgb { r: channels.next()??, g: channels.next()??, b: channels.next()?? })
}

/// Alternate tracks displayed inside lyric styles. The shared overlay has its own row layout.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum SubtitleContentMode {
    #[default]
    Translation,
    Romanization,
    Both,
    None,
}

impl SubtitleContentMode {
    pub fn single(self, line: &Line) -> Option<&str> {
        match self {
            Self::Translation | Self::Both => line.translation.as_deref(),
            Self::Romanization => line.romanization.as_deref(),
            Self::None => None,
        }
        .map(str::trim)
        .filter(|text| !text.is_empty())
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Typography {
    pub family: Option<iced_core::font::Family>,
    pub weight: Option<iced_core::font::Weight>,
    pub subtitle: Option<Font>,
    pub subtitle_scale: f32,
    pub subtitle_mode: SubtitleContentMode,
    pub hide_subtitle: bool,
}
impl Default for Typography {
    fn default() -> Self {
        Self {
            family: None,
            weight: None,
            subtitle: None,
            subtitle_scale: 1.0,
            subtitle_mode: SubtitleContentMode::Translation,
            hide_subtitle: false,
        }
    }
}
impl Typography {
    pub fn font(self, default: Font) -> Font {
        Font { family: self.family.unwrap_or(default.family), weight: self.weight.unwrap_or(default.weight), ..default }
    }

    pub fn fingerprint(self) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut hash = std::collections::hash_map::DefaultHasher::new();
        (
            self.family,
            self.weight,
            self.subtitle,
            self.subtitle_scale.to_bits(),
            self.subtitle_mode as u8,
            self.hide_subtitle,
        )
            .hash(&mut hash);
        hash.finish()
    }
}

/// Monet-specific appearance from upstream; the Android player shares an older core theme ABI.
#[derive(Debug, Default)]
pub struct MonetAppearance {
    /// The Android shell draws the interactive theme capsule and its settings bubble.
    pub shell_capsule: bool,
    /// Android mounts its native ticker in the computed rail rectangle.
    pub native_ticker: bool,
    /// Android safe drawing insets in stage dp.
    pub mobile_insets: [f32; 4],
    pub typography: Typography,
    pub word_colors: Vec<WordColor>,
    pub lyrics_icons: Vec<String>,
    pub description: Option<String>,
}
