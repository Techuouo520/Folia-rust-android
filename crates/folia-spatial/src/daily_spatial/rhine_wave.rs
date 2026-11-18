//! Playback-centred compact wave. Shape is cached once, cards retain velocity and acceleration on retarget.
use super::{HashMap,OnceLock,Spring,block,block_index};
pub(super) const PEAK:f32=1.20; // Only the active card reaches this extraction height.
pub(super) const COVER_OCCLUSION:f32=0.385; // Within the requested 35–40% lower-cover overlap.
pub(super) const BROWSE_STRENGTH:f32=0.92;
const HILL_CREST:f32=0.56;
const HILL_FOOT:f32=0.09;
#[derive(Clone,Copy)]struct Curve{start:f32,velocity:f32,acceleration:f32,time:f32,duration:f32}
#[derive(Clone,Copy)]struct WaveSpring{motion:Spring,acceleration:f32,duration:f32,delay:f32,curve:Option<Curve>,pending:Option<(f32,f32)>}
impl WaveSpring{
 fn activate(&mut self,target:f32){
  self.motion.target=target;
  let distance=(target-self.motion.value).abs();
  if distance<0.00001&&self.motion.velocity.abs()<0.0001&&self.acceleration.abs()<0.001{self.motion.jump(target);self.acceleration=0.;self.curve=None;return}
  let base=self.duration;
  // Bound inherited momentum without resetting its derivatives. Each card uses its
  // own stable duration; a near-target reversal must not take a long overshoot arc.
  let velocity_limit=if self.motion.velocity.abs()>0.001{(2.*distance/self.motion.velocity.abs()).max(0.20)}else{base};
  let acceleration_limit=if self.acceleration.abs()>0.001{(4.*distance/self.acceleration.abs()).sqrt().max(0.20)}else{base};
  let duration=base.min(velocity_limit).min(acceleration_limit);
  self.curve=Some(Curve{start:self.motion.value,velocity:self.motion.velocity,acceleration:self.acceleration,time:0.,duration});
 }
 fn advance(&mut self,dt:f32){
  let Some(c)=&mut self.curve else{return};c.time=(c.time+dt).min(c.duration);let u=c.time/c.duration;
  let u2=u*u;let v=1.-u;let v2=v*v;let v3=v2*v;let v4=v3*v;let v5=v4*v;
  // Asymmetric seventh-order easing: speed peaks at 1/3, then decelerates for
  // the remaining 2/3. Evaluate the tail in (1-u) to avoid f32 cancellation.
  let blend=if u<0.5{u2*u*(35.+u*(-105.+u*(126.+u*(-70.+15.*u))))}else{1.-v5*(21.-35.*v+15.*v2)};
  let tangent=u*v5*(1.+5.*u);let acceleration_basis=0.5*u2*v5;
  let distance=self.motion.target-c.start;
  self.motion.value=c.start+distance*blend+c.velocity*c.duration*tangent+c.acceleration*c.duration*c.duration*acceleration_basis;
  self.motion.velocity=distance*105.*u2*v4/c.duration+c.velocity*v4*(1.+4.*u-35.*u2)+c.acceleration*c.duration*u*v4*(1.-3.5*u);
  self.acceleration=distance*210.*u*v3*(1.-3.*u)/(c.duration*c.duration)
   +c.velocity*30.*u*v3*(7.*u-3.)/c.duration+c.acceleration*v3*(1.-12.*u+21.*u2);
  if c.time==c.duration{self.motion.jump(self.motion.target);self.acceleration=0.;self.curve=None;}
 }
 fn step(&mut self,dt:f32){
  let mut remaining=dt;
  if let Some((target,delay))=self.pending{
   let wait=remaining.min(delay);self.advance(wait);remaining-=wait;
   if delay>wait{self.pending=Some((target,delay-wait));return}
   self.pending=None;self.activate(target);
  }
  self.advance(remaining);
 }
}
// Each crossed world lane adds a stable 50–70 ms. Both sides spread from the playing lane.
fn lane_delay(origin:i32,lane:i32)->f32{
 (origin.min(lane)..origin.max(lane)).map(|edge|0.060+0.010*noise(edge,0x4ac21)).sum()
}
#[derive(Clone,Copy)]pub(super) struct Sightline{
 pub column_x:f32,pub row_x:f32,pub overlap_x:f32,
 pub row_height:f32,pub thickness_height:f32,
}
#[derive(Clone,Copy)]struct Lane{phase_a:f32,phase_b:f32,response:usize}
const LANES:usize=256;
fn hash(mut n:u32)->u32{n^=n>>16;n=n.wrapping_mul(0x7feb352d);n^=n>>15;n=n.wrapping_mul(0x846ca68b);n^(n>>16)}
fn noise(x:i32,seed:u32)->f32{(hash((x.rem_euclid(LANES as i32)as u32)^seed)>>8)as f32/16777215.*2.-1.}
// Stable, locally correlated card cadence. Computed only when a card joins the wave.
fn card_duration(x:i32,z:i32)->f32{
 let seed=hash(x as u32^0x75bf1);
 let drift=(noise(z-1,seed)+2.*noise(z,seed)+noise(z+1,seed))*0.25;
 0.88+0.09*drift+0.004*lane(x).response as f32
}
fn card_delay(x:i32,z:i32,dz:i32)->f32{
 (dz.abs().min(8)as f32)*0.003*(1.+0.15*noise(z,hash(x as u32^0x9837)))
}
fn lane(x:i32)->Lane{
 static LANES_CACHE:OnceLock<[Lane;LANES]>=OnceLock::new();
 let lanes=LANES_CACHE.get_or_init(||{let mut values=[Lane{phase_a:0.,phase_b:0.,response:0};LANES];for(i,value)in values.iter_mut().enumerate(){let x=i as i32;
  let drift=(noise(x-1,0x72d91)+2.*noise(x,0x72d91)+noise(x+1,0x72d91))*0.25;
  *value=Lane{phase_a:8.*drift+3.*noise(x,0x341b7),phase_b:9.*noise(x,0x81c45),response:(hash(i as u32^0x9a53d)&7)as usize};
 }values});lanes[x.rem_euclid(LANES as i32)as usize]
}
pub(super) struct Wave{springs:HashMap<usize,WaveSpring>,pub changed:Vec<usize>,sightline:Option<Sightline>,playing:Option<usize>,duration_scale:f32,strength:f32}
fn smooth(t:f32)->f32{let t=t.clamp(0.,1.);t*t*t*(10.+t*(-15.+6.*t))}
// Only the playing lane gets an extraction-height crest. Side lanes carry lower,
// differently placed shoulders, not mirrored copies of the same opening gesture.
fn side_weight(x:i32)->f32{
 if x==0{return 1.}let taper=smooth(1.-(x as f32).abs()/3.25);
 taper*taper*if x<0{0.32}else{0.21}
}
#[derive(Clone,Copy)]struct Profile{dx:i32,dz:i32,cross:f32,shoulder:f32,release:f32}
fn profile()->&'static [Profile]{
 static PROFILE:OnceLock<Vec<Profile>>=OnceLock::new();
 PROFILE.get_or_init(||{let mut points=Vec::with_capacity(175);
  for x in -3_i32..=3{for z in -10_i32..=14{
   let cross=side_weight(x);
   // The surrounding hill is independent of the one extracted card. Both sides
   // share a rounded crown; the front has a longer, soft descent. Cache all spatial
   // polynomial weights once, preserving the existing bounded neighbourhood.
   let depth=z as f32+if x<0{-1.35}else if x>0{2.10}else{0.};
   let(span,tail)=if depth<0.{(8.,2.)}else{(10.,4.)};
   let t=(depth.abs()/span).min(1.);
   // Broaden the crown without extending its cached footprint. The composed
   // profile still has zero first/second derivatives at both endpoints.
   let shoulder=1.-smooth(t*(0.75+0.25*t));let release=smooth((depth.abs()-span)/tail);
   if release<1.{points.push(Profile{dx:x,dz:z,cross,shoulder,release});}
  }}points
 })
}
// Quiet standing waves in every lane: indexed by world cells, never by camera or time.
// A small periodic table is prepared with the scene, not evaluated trigonometrically per frame.
pub(super) fn ambient(cell:usize)->f32{
 static TABLE:OnceLock<[f32;LANES*48]>=OnceLock::new();
 let heights=TABLE.get_or_init(||{let mut values=[0.;LANES*48];for x in 0..LANES{let phase=lane(x as i32);for z in 0..48{
  let a=std::f32::consts::TAU*(z as f32+phase.phase_a)/24.;
  let b=std::f32::consts::TAU*(z as f32+phase.phase_b)/48.;
  values[x*48+z]=0.05+0.28*(0.5+0.5*a.sin())+0.12*(0.5+0.5*b.cos());
 }}values});let(x,z)=block(cell);heights[x.rem_euclid(LANES as i32)as usize*48+z.rem_euclid(48)as usize]

}
impl Wave{
 pub fn new()->Self{let _=profile();let _=ambient(0);Self{springs:HashMap::with_capacity(256),changed:Vec::with_capacity(256),sightline:None,playing:None,duration_scale:1.,strength:1.}}
 pub fn browse()->Self{let mut wave=Self::new();wave.duration_scale=0.52;wave.strength=BROWSE_STRENGTH;wave}
 pub fn clear(&mut self){self.springs.clear();self.changed.clear();self.playing=None;}
 #[cfg(test)]
 pub fn is_empty(&self)->bool{self.springs.is_empty()}
 pub fn height(&self,cell:usize)->f32{self.springs.get(&cell).map(|s|s.motion.value).unwrap_or(0.)}
 #[cfg(test)]
 pub fn velocity(&self,cell:usize)->f32{self.springs.get(&cell).map(|s|s.motion.velocity).unwrap_or(0.)}
 pub fn mark_all_changed(&mut self){
  // Keep the final zero-height update of a spring retired by step this frame.
  self.changed.retain(|cell|!self.springs.contains_key(cell));
  self.changed.extend(self.springs.keys().copied());
 }
 pub fn set_sightline(&mut self,value:Sightline){self.sightline=Some(value);}
 pub fn retarget(&mut self,playing:Option<usize>){
  if playing==self.playing{return}self.playing=playing;
  for entry in self.springs.values_mut(){entry.pending=Some((0.,entry.delay));}
  if let Some(cell)=playing{let(x,z)=block(cell);
   // Size the entire hill to the first foreground sightline, rather than keeping
   // rear neighbours at PEAK and abruptly cutting the front down to the lip.
   // Inspection uses a slightly lower active card. Compute its real height
   // before the sightline cap, rather than spending an extra 8% on occlusion.
   let active_height=ambient(cell)+self.strength*(PEAK-ambient(cell));
   let lip=self.sightline.map(|s|(active_height-super::rhine_mesh::TOP-super::rhine_mesh::ART*0.5+s.row_height-s.thickness_height+super::rhine_mesh::ART*COVER_OCCLUSION).max(0.03)).unwrap_or(HILL_CREST);
   let crest=HILL_CREST.min(lip);let foot=HILL_FOOT.min(crest);
   for &Profile{dx,dz,cross,shoulder,release}in profile(){
   let id=block_index(x+dx,z+dz);let base=ambient(id);
   let hill=foot+(crest-foot)*shoulder;
   let envelope=hill+(base-hill)*release;
   let mut final_height=base+(envelope-base)*cross;
   if let Some(s)=self.sightline{if dz>0&&(dx as f32*s.column_x+dz as f32*s.row_x).abs()<s.overlap_x{
    final_height=final_height.min(lip+(dz as f32-1.)*s.row_height);
   }}
   // Existing scene blending adds the ambient field. Invert that blend for the
   // neighbours, while the single active card alone retains its original peak.
   let target=if dx==0&&dz==0{PEAK}else{(final_height-base)/(self.strength*(1.-base/PEAK))};
   let entry=self.springs.entry(id).or_insert_with(||WaveSpring{motion:Spring::new(0.),acceleration:0.,duration:card_duration(x+dx,z+dz)*self.duration_scale,delay:0.,curve:None,pending:None});entry.delay=lane_delay(x,x+dx)+card_delay(x+dx,z+dz,dz);entry.pending=Some((target,entry.delay));
  }}
 }
 pub fn step(&mut self,dt:f32)->bool{
  self.changed.clear();if self.springs.is_empty(){return false}
  // Polynomial only: no per-frame exp/trig or scheduled Java callbacks. Pending rows
  // continue their current curve, then inherit its exact value/velocity at the delay boundary.
  let t=dt.max(0.);let mut moving=false;
  for(&cell,entry)in self.springs.iter_mut(){let old=entry.motion.value;entry.step(t);
   moving|=entry.pending.is_some()||entry.curve.is_some();if old!=entry.motion.value{self.changed.push(cell);}
  }
  self.springs.retain(|_,entry|{let s=&entry.motion;entry.pending.is_some()||entry.curve.is_some()||s.target!=0.||s.value!=0.||s.velocity!=0.});moving
 }
}

#[cfg(test)]mod tests{
 use super::*;
 #[test]fn dependent_pose_invalidation_preserves_retired_cards_final_update(){
  let mut w=Wave::new();w.retarget(Some(0));w.step(2.);
  w.mark_all_changed();assert_eq!(w.changed.len(),w.springs.len());
  w.retarget(None);w.step(2.);assert!(w.is_empty());assert!(!w.changed.is_empty());
  let retired=w.changed.clone();w.mark_all_changed();assert_eq!(w.changed,retired);
 }
 #[test]fn one_card_rises_above_a_rounded_hill_with_a_longer_front_slope(){
  let mut w=Wave::new();w.retarget(Some(0));for _ in 0..200{w.step(1./120.);}
  let height=|z:i32|{let id=block_index(0,z);let base=ambient(id);let rise=w.height(id);rise+base*(1.-rise/PEAK)};
  assert_eq!(height(0),PEAK);
  assert!((height(-1)-height(1)).abs()<0.005,"neighbours must share one crown, not a cliff");
  for side in [-1,1]{let span=if side<0{8}else{10};for z in 1..span{
   assert!(height(side*z)<=HILL_CREST);assert!(height(side*z)>height(side*(z+1)));
   assert!((height(side*z)-height(side*(z+1))).abs()<(HILL_CREST-HILL_FOOT)*0.26,"smooth spatial slope");
  }}
  assert!(height(5)>height(-5),"front slope has a longer tail");assert_eq!(w.height(block_index(4,0)),0.);
 }
 #[test]fn rapid_retarget_preserves_height_velocity_and_frame_rate(){let mut a=Wave::new();let mut b=Wave::new();
  for cell in [0,40,0,120,8]{a.retarget(Some(cell));b.retarget(Some(cell));for _ in 0..3{a.step(1./30.);}for _ in 0..12{b.step(1./120.);}for(&id,entry)in &a.springs{let s=&entry.motion;assert!((s.value-b.height(id)).abs()<0.00002);assert!(s.value.is_finite()&&s.value.abs()<PEAK*1.1);}
   let before=a.springs.clone();a.retarget(Some(cell));for(&id,entry)in &before{let s=&entry.motion;assert_eq!(a.height(id),s.value);assert_eq!(a.velocity(id),s.velocity);}
  }
  a.retarget(None);for _ in 0..240{a.step(1./120.);}assert!(a.is_empty());assert!(!a.step(0.1));assert!(a.changed.is_empty());
 }
 #[test]fn ambient_lanes_are_bounded_continuous_and_do_not_schedule_frames(){
  for x in -20..20{let mut lo=f32::INFINITY;let mut hi=f32::NEG_INFINITY;for z in -40..40{let a=ambient(block_index(x,z));lo=lo.min(a);hi=hi.max(a);assert!((0.0499..=0.4501).contains(&a));assert!((a-ambient(block_index(x,z+1))).abs()<0.045);assert_eq!(a,ambient(block_index(x+LANES as i32,z+48)));}assert!(hi-lo>0.05);}
  let mut wave=Wave::new();assert!(!wave.step(0.1));assert!(wave.changed.is_empty());
 }

 #[test]fn lanes_have_visible_stable_irregular_phase_and_response_offsets(){
  let mut signs=std::collections::HashSet::new();let mut speeds=std::collections::HashSet::new();
  for x in 0..24{let a=lane(x);let b=lane(x+1);assert!(a.phase_a.abs()<=11.&&a.phase_b.abs()<=9.);assert_eq!(a.phase_a,lane(x).phase_a);signs.insert((b.phase_a-a.phase_a).is_sign_positive());speeds.insert(a.response);}
  assert_eq!(signs.len(),2);assert!(speeds.len()>=6);
  let mut crests=std::collections::HashSet::new();let mut spread=0_f32;
  for x in 0..24{let crest=(0..48).max_by(|&a,&b|ambient(block_index(x,a)).total_cmp(&ambient(block_index(x,b)))).unwrap();crests.insert(crest);
   spread=spread.max((ambient(block_index(x,60))-ambient(block_index(x+1,60))).abs());
  }
  assert!(crests.len()>=8,"distant lanes must not share one ridge");assert!(spread>0.10,"phase differences must remain visible away from playback");
  let mut w=Wave::new();w.retarget(Some(0));w.step(0.08);let mut responses=Vec::new();
  for dx in -3..=3{let id=block_index(dx,0);responses.push(w.height(id));}
  assert!(responses.windows(2).any(|p|(p[0]-p[1]).abs()>0.01));
 }

 #[test]fn rows_start_after_cumulative_50_to_70ms_and_use_eased_motion(){
  for x in -20..20{assert!((0.050..=0.070).contains(&lane_delay(x,x+1)));}
  let mut w=Wave::new();w.retarget(Some(0));let near=block_index(1,0);let far=block_index(2,0);let delay=lane_delay(0,1);
  assert!(w.step(delay-0.001));assert_eq!(w.height(near),0.);assert_eq!(w.height(far),0.);assert!(w.height(0)>0.);
  w.step(0.002);assert!(w.height(near)>0.);assert_eq!(w.height(far),0.);
  let mut e=Wave::new();e.retarget(Some(0));e.step(0.05);let first=e.height(0);e.step(0.05);assert!(e.height(0)-first>first*2.);
 }
 #[test]fn pending_rows_keep_moving_and_obsolete_targets_never_fire(){
  let mut w=Wave::new();w.retarget(Some(0));w.step(0.14);let cell=block_index(1,0);let height=w.height(cell);let speed=w.velocity(cell);
  w.retarget(Some(block_index(-1,0)));assert_eq!(w.height(cell),height);assert_eq!(w.velocity(cell),speed);w.step(0.01);assert!(w.height(cell)>height);
  w.retarget(None);for _ in 0..240{w.step(1./120.);}assert!(w.is_empty());
 }

 #[test]fn side_lanes_are_low_and_do_not_mirror_the_playing_crest(){
  let mut w=Wave::new();w.retarget(Some(0));for _ in 0..160{w.step(1./120.);}
  let mut left_peak=0_f32;let mut right_peak=0_f32;let mut different=false;
  for z in -10..=14{let left=w.height(block_index(-1,z));let right=w.height(block_index(1,z));left_peak=left_peak.max(left);right_peak=right_peak.max(right);different|=(left-right).abs()>0.005;}
  assert!(left_peak<PEAK*0.25&&right_peak<PEAK*0.18);assert!(different);assert_eq!(w.height(0),PEAK);
 }

 #[test]fn each_card_has_its_own_eased_cadence_and_soft_endpoints(){
  let mut durations=std::collections::HashSet::new();
  for z in -8..=8{let duration=card_duration(0,z);assert!((0.79..=0.998).contains(&duration));durations.insert((duration*10000.)as i32);
   let mut card=WaveSpring{motion:Spring::new(0.),acceleration:0.,duration,delay:0.,curve:None,pending:None};card.activate(1.);
   card.advance(duration*0.1);let first=card.motion.value;card.advance(duration*0.1);let next=card.motion.value-first;assert!(next>first*2.);
   assert!(card.motion.velocity>0.);card.advance(duration);assert_eq!(card.motion.value,1.);assert_eq!(card.motion.velocity,0.);assert_eq!(card.acceleration,0.);assert!(card.curve.is_none());
  }
  assert!(durations.len()>=12,"same-lane cards must not march with identical timing");
 }
 #[test]fn interruption_preserves_acceleration_as_well_as_position_and_velocity(){
  let mut card=WaveSpring{motion:Spring::new(0.),acceleration:0.,duration:0.64,delay:0.,curve:None,pending:None};card.activate(1.);card.advance(0.14);
  let(value,velocity,acceleration)=(card.motion.value,card.motion.velocity,card.acceleration);assert!(acceleration.abs()>1.);
  card.activate(0.2);card.advance(0.);assert_eq!(card.motion.value,value);assert_eq!(card.motion.velocity,velocity);assert!((card.acceleration-acceleration).abs()<0.00001);
  // Use a local derivative probe: a 0.1ms forward difference also includes the
  // intentional jerk of a short reversal, not only acceleration at the handoff.
  card.advance(0.00001);assert!(((card.motion.velocity-velocity)/0.00001-acceleration).abs()<0.2);
  card.advance(1.);assert_eq!(card.motion.value,0.2);assert_eq!(card.motion.velocity,0.);assert_eq!(card.acceleration,0.);
 }

 #[test]fn last_two_thirds_decelerate_continuously_in_both_directions(){
  for (start,target) in [(0.,1.),(1.,0.)]{
   let mut card=WaveSpring{motion:Spring::new(start),acceleration:0.,duration:0.9,delay:0.,curve:None,pending:None};card.activate(target);
   let mut previous_speed=f32::INFINITY;let mut previous_value=start;let mut last=0.;
   for phase in [0.34,0.45,0.60,0.75,0.85,0.95]{card.advance((phase-last)*0.9);last=phase;
    let speed=card.motion.velocity.abs();assert!(speed>0.&&speed<previous_speed);assert!(card.acceleration*(target-start)<0.);
    assert!((card.motion.value-previous_value)*(target-start)>0.);assert!(card.curve.is_some());previous_speed=speed;previous_value=card.motion.value;
   }
   assert!((target-card.motion.value).abs()<0.0001);card.advance(0.9);assert_eq!(card.motion.value,target);assert_eq!(card.motion.velocity,0.);assert_eq!(card.acceleration,0.);
  }
 }

}
