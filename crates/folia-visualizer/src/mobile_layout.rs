//! Android landscape poster geometry, in dp. The shell and native rail use this same contract.
#[derive(Clone, Copy, Debug)]
pub struct Landscape {
    pub card: [f32; 4],
    pub cover_height: f32,
    pub rail: [f32; 4],
    pub main_px: f32,
    pub subtitle_px: f32,
}
impl Landscape {
    pub fn packed(self) -> Vec<f32> {
        let mut values = self.card.to_vec();
        values.push(self.cover_height);
        values.extend(self.rail);
        values.extend([self.main_px, self.subtitle_px]);
        values
    }
}
pub fn landscape(width: f32, height: f32, insets: [f32; 4], font_scale: f32) -> Landscape {
    let [left, top, right, bottom] = insets.map(|v| v.max(0.));
    let usable_w = (width - left - right).max(1.);
    let usable_h = (height - top - bottom).max(1.);
    // The cover is square; only its control footer extends below it. With the
    // corners relocated there is no longer a 68dp empty strip above the card.
    let control_h = 96.;
    let card_w = (usable_w * 0.30).min(328.).min((usable_h - 48. - control_h).max(1.));
    let cover_h = card_w;
    let card_h = cover_h + control_h;
    // Center the cover in real breathing room, rather than a 10dp edge slot.
    // Both gutters grow with the usable window; system/cutout insets are additional.
    let gutter = (usable_w * 0.045).clamp(28.,56.);
    let card_x = width - right - gutter - card_w;
    let card_y = top + (usable_h-card_h)*0.5;
    let rail_x = left + 24.;
    let rail_y = top + 104.;
    let rail_w = (card_x - gutter - rail_x).max(1.);
    let rail_h = (height - bottom - 64. - rail_y).max(1.);
    // Three main rows, their translations, 4% focus growth and sentence spacing all fit.
    let main_px = ((usable_w * 0.034).clamp(18., 36.) * font_scale.clamp(0.5, 2.))
        .min((rail_h / 3. - 4.).max(1.) / ((1.3 + 0.60 * 1.25 + 0.42) * 1.04)).max(1.);
    Landscape { card: [card_x, card_y, card_w, card_h], cover_height: cover_h,
        rail: [rail_x, rail_y, rail_w, rail_h], main_px, subtitle_px: main_px * 0.60 }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn landscape_fits_phones_tablets_and_large_fonts() {
        for (w,h) in [(640.,320.),(800.,360.),(932.,430.),(1280.,800.),(1600.,900.)] {
            for scale in [0.5,1.,1.5,2.] {
                let p=landscape(w,h,[32.,24.,24.,24.],scale);
                let gutter=(w-32.-24.)*0.045;
                let gutter=gutter.clamp(28.,56.);
                assert!((p.card[0]-(p.rail[0]+p.rail[2])-gutter).abs()<0.001);
                assert!((w-24.-p.card[0]-p.card[2]-gutter).abs()<0.001);
                assert!(p.card[1]+p.card[3]<=h-24.+0.001);
                assert!(3.*(p.main_px*(1.3+0.60*1.25+0.42)*1.04+4.)<=p.rail[3]+0.001);
                assert_eq!(p.cover_height,p.card[2]);
                let rail_right=p.rail[0]+p.rail[2];
                assert!((p.card[0]+p.card[2]*0.5-(rail_right+w-24.)*0.5).abs()<0.001);
                assert!(p.card[1]>=24.+24.-0.001);
                assert!(p.card[1]+p.card[3]<=h-24.-24.+0.001);
            }
        }
    }
}
