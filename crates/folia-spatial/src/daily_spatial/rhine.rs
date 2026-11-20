//! Rhine archive for phone and Pad viewports. Owns camera, gestures, extraction and
//! docking independently of the card wall. Emits one coherent pose/typography/menu snapshot.
use super::*;
use super::rhine_math::{V,Q,Pose,ease,settle,soft_stop};
use super::rhine_mesh::{LEFT,RIGHT,TOP,BOTTOM,THICK,PRINT_Z};
use super::rhine_wave::{Wave,PEAK,ambient,Sightline,COVER_OCCLUSION,BROWSE_STRENGTH};
// 0: original glass, 1: matte archive, 2: theme-tinted glass with 12% transmission.
const ARCHIVE_MATERIAL:f32=4.; // 2% glass rim around a full-thickness opaque album core
const RIM_TRANSMISSION:f32=0.02;
// Aligned rows: 0.5cm side gap, 1.1cm front/back gap between exterior print surfaces.
// The fixed near view looks down at 17.5 degrees, showing real top faces and thickness.
const COLUMN_GAP:f32=0.5/30.;const DEPTH_GAP:f32=1.1/30.;
const COL:f32=RIGHT-LEFT+COLUMN_GAP;const ROW:f32=2.*PRINT_Z+DEPTH_GAP;
// Catalogue order runs down each wave lane, not around the shared square-spiral cell IDs.
const LANE_SONG_STRIDE:i64=97;
const YAW:f32=50.;const PITCH:f32=-162.5;const CAMERA_WIDTH:f32=1.75;
const CAMERA_HEIGHT:f32=3.5;
// Typography is laid out in its own face plane, then projected by the sleeve's
// actual basis. These local dp dimensions do not become screen-facing bounds.
const HOLO_WIDTH:f32=224.;const HOLO_HEIGHT:f32=56.;const HOLO_UNIT:f32=0.005;
const HOLO_FACE_SCREEN:f32=0.15;
fn hologram_basis(m:&[f32])->[V;2]{
 let normal=V(m[8],m[9],m[10]).unit();
 // Screen-facing normal is -Z. Rotate the rigid plane through 15% of
 // the remaining angle, preserving its scale and its album-relative roll.
 let toward=Q::axis(V(-normal.1,normal.0,0.),(-normal.2).clamp(-1.,1.).acos()*HOLO_FACE_SCREEN);
 [toward.rotate(V(m[0],m[1],m[2])),toward.rotate(V(-m[4],-m[5],-m[6]))]
}
fn hologram_plane(m:&[f32],alpha:f32)->[f32;6]{
 let reveal=0.94+0.06*alpha;let unit=HOLO_UNIT*reveal;
 let x=RIGHT+0.08;
 let y=TOP+0.12-(1.-alpha)*0.04;
 let z=PRINT_Z+0.01;
 let [right,down]=hologram_basis(m);let right=right.mul(unit);let down=down.mul(unit);
 // Keep the attachment at the album's upper-right fixed while turning the
 // plane toward the viewer; drawing and inverse input share this projection.
 let anchor=V(m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13],0.);
 let origin=anchor.sub(right.mul(HOLO_WIDTH)).sub(down.mul(HOLO_HEIGHT));
 [right.0,right.1,down.0,down.1,origin.0,origin.1]
}
fn hologram_bounds(r:&[f32])->[f32;4]{
 let ux=r[7]*r[4];let uy=r[9]*r[4];let vx=r[10]*r[5];let vy=r[11]*r[5];
 [r[2]+ux.min(0.)+vx.min(0.),r[3]+uy.min(0.)+vy.min(0.),
  r[2]+ux.max(0.)+vx.max(0.),r[3]+uy.max(0.)+vy.max(0.)]
}
#[derive(Clone,Copy)]struct Return{start:Pose,lead:Q,velocity:V,size_velocity:f32,time:f32,duration:f32,progress:f32}
#[derive(Clone,Copy)]struct Cell{cell:usize,song:usize,pose:Pose,distance:f32}
#[derive(Clone,Copy,Default)]struct Hologram{cell:Option<usize>,alpha:Spring}
#[derive(Clone,Copy,Debug,PartialEq)]
pub(super) struct NavigationView {pan_x:f32,pan_z:f32,turn:f32,lift:f32,selected:usize}
pub(super) struct Rhine{
 pub common:State,sequence_origin:usize,pan_x:Spring,pan_z:Spring,turn:Spring,progress:Spring,
 browsing:bool,manual_view:bool,follow_pending:bool,explicit_play:Option<usize>,browse_wave:Wave,framing:Spring,view_lift:f32,view_lift_velocity:f32,
 view_q:Q,view_basis:[V;3],waves:Wave,slots:HashMap<usize,usize>,
 focus:Option<usize>,pending:Option<usize>,returning:Option<Return>,pose:Pose,previous:Pose,velocity:V,size_velocity:f32,angular:V,
 dirty:bool,world_dirty:bool,world_signature:[u32;5],revision:u32,world_revision:u32,topology_revision:u32,cache_pan:(f32,f32),applied_pan:(f32,f32),extraction_time:f32,
 cells:Vec<Cell>,cache:Vec<f32>,motion_age:f32,holograms:[Hologram;HOLOGRAM_SLOTS],
}
impl Rhine{
 pub(super) fn navigation_view(&self)->NavigationView {
  NavigationView{pan_x:self.pan_x.value,pan_z:self.pan_z.value,turn:self.turn.value,lift:self.view_lift,selected:self.common.selected}
 }
 pub(super) fn restore_navigation_view(&mut self,v:NavigationView) {
  self.pan_x.jump(v.pan_x);self.pan_z.jump(v.pan_z);self.turn.jump(v.turn);
  self.framing.jump(v.lift);self.view_lift=v.lift;self.view_lift_velocity=0.;
  self.common.selected=v.selected;self.manual_view=true;self.follow_pending=false;self.browsing=false;
  self.world_dirty=true;self.dirty=true;
 }

 pub fn new()->Self{let mut common=State::new();common.bindings.reserve(8192);common.next_bindings.reserve(8192);common.visible.reserve(SLOTS);common.visible_songs.reserve(SLOTS);common.prefetch_seen.reserve(SLOTS+PREFETCH);let mut scene=Self{common,sequence_origin:0,pan_x:Spring::new(0.),pan_z:Spring::new(0.),turn:Spring::new(0.),progress:Spring::new(0.),browsing:false,manual_view:false,follow_pending:false,explicit_play:None,browse_wave:Wave::browse(),framing:Spring::new(0.),view_lift:0.,view_lift_velocity:0.,waves:Wave::new(),slots:HashMap::with_capacity(SLOTS),view_q:Q::default(),view_basis:[V::default();3],focus:None,pending:None,returning:None,pose:Pose::default(),previous:Pose::default(),velocity:V::default(),size_velocity:0.,angular:V::default(),dirty:true,world_dirty:true,world_signature:[u32::MAX;5],revision:0,world_revision:0,topology_revision:0,cache_pan:(0.,0.),applied_pan:(0.,0.),extraction_time:0.,cells:Vec::with_capacity(8192),cache:vec![0.;FLOATS],motion_age:1.,holograms:[Hologram::default();HOLOGRAM_SLOTS]};scene.update_camera();scene}
 // Fixed camera, shared by inverse gestures, projection, glass and picking; computed once.
 fn update_camera(&mut self){self.view_q=Q::axis(V(1.,0.,0.),PITCH.to_radians()).mul(Q::axis(V(0.,1.,0.),YAW.to_radians()));self.view_basis=[self.view_q.rotate(V(1.,0.,0.)),self.view_q.rotate(V(0.,1.,0.)),self.view_q.rotate(V(0.,0.,1.))];
  let [x,y,z]=self.view_basis;let height_per_depth=(z.1-x.1*z.0/x.0)/(-y.1);
  let sightline=Sightline{column_x:COL*x.0,row_x:ROW*z.0,overlap_x:(RIGHT.max(-LEFT)+0.5)*x.0.abs()+2.*PRINT_Z*z.0.abs(),row_height:ROW*height_per_depth,thickness_height:2.*PRINT_Z*height_per_depth};
  self.waves.set_sightline(sightline);self.browse_wave.set_sightline(sightline);
 }
 fn camera_q(&self)->Q{self.view_q}
 fn basis(&self,v:V)->V{self.view_basis[0].mul(v.0).add(self.view_basis[1].mul(v.1)).add(self.view_basis[2].mul(v.2))}
 // Input 26 arrives only after Android crosses touch slop. Playback yields while
 // dragging; input 28 restores it without moving the released camera position.
 fn sync_wave(&mut self){
  let playing=self.common.playing.filter(|_|self.common.playing_song.is_some());
  self.waves.retarget(if self.browsing{None}else{playing});
  let inspected=self.view_cell();
  // Do not recreate the same playing crest through the inspection wave as it
  // retracts. Other inspected cards retain their own hill and extraction.
  self.browse_wave.retarget(if self.manual_view&&Some(inspected)!=playing{Some(inspected)}else{None});
 }
 fn view_cell(&self)->usize{block_index((-self.pan_x.value/COL).round()as i32,(-self.pan_z.value/ROW).round()as i32)}
 fn wave_height(&self,cell:usize)->f32{
  let base=ambient(cell);let rise=self.waves.height(cell);let original=rise+base*(1.-rise/PEAK);
  // Reveal the browsed cover as a real crest, with its foreground lowered by the
  // same sightline profile as playback. A low positive bump alone leaves it hidden.
  let preview=self.browse_wave.height(cell)*BROWSE_STRENGTH*(1.-base/PEAK);
  let extra=(preview)*(1.-rise/PEAK).clamp(0.,1.);
  let mut height=(original+extra).min(PEAK);
  // Keep the existing foreground sightline while browsing past the playing card.
  if extra>0.{if let Some(playing)=self.common.playing{let(x,z)=block(cell);let(px,pz)=block(playing);let dz=z-pz;
   let [a,y,b]=self.view_basis;let slope=(b.1-a.1*b.0/a.0)/(-y.1);
   if dz>0&&dz<=14&&((x-px)as f32*COL*a.0+dz as f32*ROW*b.0).abs()<(RIGHT.max(-LEFT)+0.5)*a.0.abs()+2.*PRINT_Z*b.0.abs(){
    let cap=(PEAK-TOP-super::rhine_mesh::ART*0.5+dz as f32*ROW*slope-2.*PRINT_Z*slope+super::rhine_mesh::ART*COVER_OCCLUSION).max(0.03);
    // Fade protection with the actual playback crest, not the gesture boolean;
    // rapid drag/release must not jump existing inspection poses at the handoff.
    let protection=(self.waves.height(playing)/PEAK).clamp(0.,1.);
    height+=(height.min(cap.max(original))-height)*protection;
   }
  }}height
 }
 // Emission follows actual raised geometry, including exploration. Ambient waves
 // alone are not lamps. Smooth endpoints preserve the existing nonlinear motion.
 fn cover_emission(&self,cell:usize)->f32{
  let base=ambient(cell);ease(0.,0.9,((self.wave_height(cell)-base)/(PEAK-base)).clamp(0.,1.))
 }
 // Wave-only frames patch the already retained slots; no candidate scan, sort or rebinding.
 fn patch_wave(&mut self,shift:V){
  // Framing is one uniform translation, not a new spatial search/sort on every animation tick.
  if shift.0!=0.||shift.1!=0.||shift.2!=0.{for slot in 0..self.cache[16]as usize{let at=HEADER+slot*STRIDE;let m=RHINE_POSES+slot*16;
   self.cache[at+1]+=shift.0;self.cache[at+2]+=shift.1;self.cache[m+12]+=shift.0;self.cache[m+13]+=shift.1;self.cache[m+14]+=shift.2;
   self.common.visible[slot].1.x+=shift.0;self.common.visible[slot].1.y+=shift.1;
  }}

  for &cell in &self.waves.changed{if let Some(&slot)=self.slots.get(&cell){let p=self.source(cell,1.).p;let at=HEADER+slot*STRIDE;self.cache[at+1]=p.0;self.cache[at+2]=p.1;self.cache[at+5]=self.cover_emission(cell);let matrix=RHINE_POSES+slot*16;self.cache[matrix+12]=p.0;self.cache[matrix+13]=p.1;self.cache[matrix+14]=p.2;self.common.visible[slot].1.x=p.0;self.common.visible[slot].1.y=p.1;}}
 }
 fn song(&self,cell:usize)->usize{
  self.common.bindings.get(&cell).copied().filter(|&song|song<self.common.count).unwrap_or_else(||{
   let(c,r)=block(cell);(self.sequence_origin as i64+c as i64*LANE_SONG_STRIDE+r as i64).rem_euclid(self.common.count.max(1)as i64)as usize
  })
 }
 fn lane_cell(&self,song:usize,column:i32,row:i32)->usize{
  let count=self.common.count.max(1)as i64;
  let logical=self.song(block_index(column,row))as i64;
  let delta=(song as i64-logical).rem_euclid(count);let delta=if delta>count/2{delta-count}else{delta};
  block_index(column,row+delta as i32)
 }
 fn center_on(&mut self,cell:usize){let(c,r)=block(cell);self.common.selected=cell;self.pan_x.target=(-c)as f32*COL;self.pan_z.target=(-r)as f32*ROW;}
 // Follow the composition, not the card's instantaneous height. A dead band lets
 // adjacent songs travel through the shot; crossing it eases towards an inner edge.
 fn follow_playback(&mut self,cell:usize){
  if self.manual_view{return}
  let w=self.common.width;let h=self.common.height;if w<=0.||h<=0.{return}
  let(c,r)=block(cell);
  let offset=self.basis(V(c as f32*COL+self.pan_x.target,PEAK-self.framing.target,r as f32*ROW+self.pan_z.target)).mul(self.scale());
  let dx=if offset.0.abs()>w*0.12{offset.0.signum()*w*0.07-offset.0}else{0.};
  let dy=if offset.1.abs()>h*0.10{offset.1.signum()*h*0.06-offset.1}else{0.};
  if dx!=0.||dy!=0.{let(x,z)=self.ground(dx,dy);self.pan_x.target+=x;self.pan_z.target+=z;}
 }
 fn resume_playback_follow(&mut self){
  if !self.follow_pending||self.browsing||self.common.action_phase!=0{return}
  self.follow_pending=false;self.manual_view=false;
  if let Some(cell)=self.common.playing{self.framing.target=PEAK;self.follow_playback(cell);}
 }
 fn depth(&self)->f32{self.pan_z.value}
 fn scale(&self)->f32{
  // Fit both axes of the real dp viewport. Tall phones retain the original
  // close view; wider Pad windows reveal more columns instead of enlarging
  // each sleeve beyond the available height. Picking, dragging and culling
  // all use this same orthographic scale, including after a viewport resize.
  (self.common.width/CAMERA_WIDTH).min(self.common.height/CAMERA_HEIGHT)
 }
 fn ground(&self,x:f32,y:f32)->(f32,f32){let k=self.scale();let right=self.basis(V(1.,0.,0.));let forward=self.basis(V(0.,0.,1.));let det=right.0*forward.1-right.1*forward.0;((x*forward.1-y*forward.0)/(k*det),(y*right.0-x*right.1)/(k*det))}
 fn center(&self)->V{V(self.common.width*0.5,self.common.height*0.5-self.view_basis[1].1*self.scale()*self.view_lift,0.)}
 fn source(&self,cell:usize,background:f32)->Pose{let(c,r)=block(cell);self.source_at(c,r,background)}
 fn source_at(&self,c:i32,r:i32,background:f32)->Pose{let base=V(c as f32*COL+self.pan_x.value,self.wave_height(block_index(c,r)),r as f32*ROW+self.depth());let p=self.basis(base).mul(self.scale()).mul(background).add(self.center());Pose{p,q:self.camera_q(),size:self.scale()*background}}

 fn open(&mut self,cell:usize){
  if self.common.count==0{return}
  if let Some(old)=self.focus{if old==cell&&self.returning.is_none(){return}self.pending=Some(cell);self.close();return}
  self.focus=Some(cell);self.common.selected=cell;self.progress.jump(0.);self.progress.target=1.;self.extraction_time=0.;self.turn.jump(0.);self.returning=None;self.pose=self.source(cell,1.);self.previous=self.pose;self.velocity=V::default();self.size_velocity=0.;self.angular=V::default();self.motion_age=1.;self.world_dirty=true;self.dirty=true;
 }
 fn close(&mut self){if self.focus.is_none()||self.returning.is_some(){return}let duration=0.46+0.28*self.progress.value.clamp(0.,1.);let angular=if self.motion_age<0.09{self.angular}else{V::default()};let speed=angular.norm();let lead=self.pose.q.mul(Q::axis(angular,(speed*duration*0.54/3.).min(0.4)));self.returning=Some(Return{start:self.pose,lead,velocity:if self.motion_age<0.09{self.velocity}else{V::default()},size_velocity:if self.motion_age<0.09{self.size_velocity}else{0.},time:0.,duration,progress:self.progress.value.max(0.001)});self.progress.target=0.;self.progress.velocity=0.;self.dirty=true;}
 fn clear_context(&mut self){self.common.close_actions();self.common.action_cell=None;}
 fn finish_removal(&mut self){let hidden=self.common.hidden_cell.take();self.clear_context();if let Some(cell)=hidden{self.common.fill_cell=Some(cell);self.common.fill.jump(0.);self.common.fill.target=1.;}self.focus=None;self.pending=None;self.returning=None;self.progress.jump(0.);self.world_dirty=true;self.dirty=true;}
 pub fn cancel_transient(&mut self){self.explicit_play=None;self.browsing=false;self.sync_wave();if self.common.hidden_cell.is_some(){self.finish_removal();}self.clear_context();self.common.menu.jump(0.);self.common.confirmation.jump(0.);self.common.lift.jump(0.);self.common.lift_shadow.jump(0.);self.focus=None;self.pending=None;self.returning=None;self.progress.jump(0.);self.world_dirty=true;self.dirty=true;self.common.pinching=false;}
 pub fn command(&mut self,op:i32,index:i32,a:f32,b:f32)->bool{
  // Gesture motion is disabled at the native boundary as well as the Android adapter.
  // Playback follows a loose composition band; explicit locate still centers exactly.
  if matches!(op,4|5|6|11|12|13|24|25){return false}
  self.dirty=true;
  match op{
   26=>{if self.common.count==0||self.common.action_phase!=0||self.focus.is_some(){return false}
    self.browsing=true;self.manual_view=true;self.pan_x.target=self.pan_x.value;self.pan_z.target=self.pan_z.value;
    // Inspection raises its own crest even before the first song is played.
    // Use the same eased height compensation as the established playing view.
    self.framing.target=PEAK;}
   27=>{if !self.browsing||!a.is_finite()||!b.is_finite()||self.common.width<=0.{return false}
    let(x,z)=self.ground(a.clamp(-self.common.width,self.common.width),b.clamp(-self.common.height,self.common.height));
    self.pan_x.target+=x;self.pan_z.target+=z;
   }
   28=>{self.browsing=false;self.resume_playback_follow();self.sync_wave();}
   29=>{self.explicit_play=if index>=0&&self.song(self.common.selected)==index as usize{Some(self.common.selected)}else{None};}
   0=>{if index<0||index>1_000_000{return false}self.common.count=index as usize;self.world_dirty=true;}
   10=>{if index<0||index>1_000_000{return false}self.cancel_transient();self.follow_pending=false;self.manual_view=false;self.browse_wave.clear();self.holograms=[Hologram::default();HOLOGRAM_SLOTS];self.common.count=index as usize;self.common.bindings.clear();let selected=(a.max(0.)as usize).min(self.common.count.saturating_sub(1));self.common.playing_song=if b>=0.&&(b as usize)<self.common.count{Some(b as usize)}else{None};self.sequence_origin=self.common.playing_song.unwrap_or(selected);self.common.selected=self.lane_cell(selected,0,0);self.common.playing=self.common.playing_song.map(|_|block_index(0,0));self.waves.clear();self.sync_wave();self.framing.jump(if self.common.playing.is_some(){PEAK}else{0.});self.view_lift=self.framing.value;self.view_lift_velocity=0.;self.center_on(self.common.playing.unwrap_or(self.common.selected));self.pan_x.jump(self.pan_x.target);self.pan_z.jump(self.pan_z.target);self.world_dirty=true;}
   1=>{if index<0||self.common.count==0{return false}self.common.selected=index as usize;}
   2=>{let song=if index>=0&&(index as usize)<self.common.count{Some(index as usize)}else{None};let explicit=self.explicit_play.filter(|&cell|Some(self.song(cell))==song);
    let changed=song!=self.common.playing_song||(explicit.is_some()&&explicit!=self.common.playing);self.common.playing_song=song;self.common.running=a>0.5;if changed{if song.is_none(){self.follow_pending=false;}self.common.playing=song.map(|s|self.nearest_song(s));self.explicit_play=None;if let Some(cell)=self.common.playing{self.common.bindings.insert(cell,song.unwrap());self.follow_pending=true;
    // Initial composition is placed once. Later changes preserve camera momentum
    // and move only when the new crest crosses the composition band.
    if self.world_signature[0]==u32::MAX{self.center_on(cell);self.pan_x.jump(self.pan_x.target);self.pan_z.jump(self.pan_z.target);self.framing.jump(PEAK);self.view_lift=PEAK;self.view_lift_velocity=0.;}else{self.framing.target=PEAK;if self.common.action_phase==0{self.follow_playback(cell);}}
   }self.resume_playback_follow();self.sync_wave();self.world_dirty=true;}}
   14=>{let Some(song)=self.common.playing_song else{return false};let cell=self.common.playing.unwrap_or_else(||self.nearest_song(song));self.browsing=false;self.follow_pending=false;self.manual_view=false;self.browse_wave.retarget(None);self.center_on(cell);self.framing.target=PEAK;}
   3=>{self.common.mode.target=if index!=0{1.}else{0.};if index==0{self.browsing=false;self.clear_context();self.pending=None;self.close();}else if let Some(cell)=self.common.playing{self.framing.target=PEAK;self.follow_playback(cell);}self.sync_wave();}
   8=>{self.cancel_transient();self.common.mode.jump(0.);}
   7=>{self.common.header=a.max(0.);self.common.scroll=b.max(0.);}

   23=>{self.pending=None;self.clear_context();self.close();}
   15=>{if index<0||self.common.count==0||self.common.hidden_cell.is_some(){return false}let cell=index as usize;if !self.common.visible.iter().any(|v|v.0==cell){return false}self.open(cell);self.common.action_cell=Some(cell);self.common.action_phase=1;self.common.lift.target=1.;self.common.lift_shadow.target=1.;self.common.menu.target=0.;}
   16=>{if self.common.action_phase!=3{self.clear_context();self.pending=None;self.close();}}
   17=>{if self.common.action_phase!=1{return false}self.common.action_phase=2;self.common.menu.target=0.;}
   18=>{if self.common.action_phase!=2{return false}self.common.capture_hold=false;self.common.action_phase=3;self.common.hidden_cell=self.focus;self.common.confirmation.target=0.;self.common.lift_shadow.target=0.;}
   19=>{if self.common.hidden_cell.is_some(){self.finish_removal();}else if self.common.action_phase==3{self.clear_context();}}
   20=>{if index<0||index as usize>=self.common.count{return false}let removed=index as usize;let removes=self.focus.map(|c|self.song(c))==Some(removed);self.common.count-=1;if self.sequence_origin>removed{self.sequence_origin-=1;}else if self.sequence_origin==removed{self.sequence_origin=removed.min(self.common.count.saturating_sub(1));}for song in self.common.bindings.values_mut(){if *song>removed{*song-=1;}else if *song==removed{*song=removed.min(self.common.count.saturating_sub(1));}}if let Some(song)=self.common.playing_song{if song==removed{self.common.playing_song=None;self.common.playing=None;self.common.running=false;}else if song>removed{self.common.playing_song=Some(song-1);}}if removes{self.finish_removal();}self.sync_wave();self.holograms=[Hologram::default();HOLOGRAM_SLOTS];self.world_dirty=true;}
   21=>{if self.common.action_phase!=2||self.focus!=Some(index as usize){return false}self.common.capture_hold=true;}
   22=>self.common.capture_hold=false,
   _=>return false,
  }true
 }
 fn nearest_song(&self,song:usize)->usize{
  // Honour an explicitly selected spatial instance (double tap / menu play).
  if let Some(cell)=self.explicit_play{if self.song(cell)==song{return cell}}
  let(c,r)=block(self.common.playing.unwrap_or(self.common.selected));
  // Append changes the catalogue modulus; retained visible covers still own their old
  // identities. Resolve those first, before inventing a far-away copy using the new count.
  if let Some(cell)=self.common.bindings.iter().filter(|(cell,bound)|**bound==song&&block(**cell).0==c)
   .min_by_key(|(cell,_)|(block(**cell).1-r).abs()).map(|(&cell,_)|cell){return cell}
  self.lane_cell(song,c,r)
 }
 // Canonical typography is laid out in the fully extracted face, never a
 // foreshortened projection during rotation. It stays the same across reopen.
 fn caption_reference_size(&self)->f32{
  let available=(self.common.height-76.-210.-136.).max(100.)/(4./3.);
  (self.common.width*0.78/1.2).min(available).min((self.common.width-32.)/1.2).max(1.)
 }
 fn advance_pose(&mut self,dt:f32){let Some(cell)=self.focus else{return};let p=self.progress.value.clamp(0.,1.);let source=self.source(cell,1.-0.055*p);let clearance=self.basis(V(0.,1.46,0.)).mul(self.scale());let old=self.pose;
  if let Some(r)=self.returning{let u=(r.time/r.duration).clamp(0.,1.);let raised=source.p.add(clearance.mul((r.progress/0.35).min(1.)));let pos=settle(r.start.p,raised,r.velocity,r.duration*0.72,(u/0.72).clamp(0.,1.)).mix(source.p,ease(0.56,1.,u));let t=soft_stop(u/0.54);let q0=r.start.q.mix(r.lead,t);let q1=r.lead.mix(source.q,t);let q=q0.mix(q1,t).mix(q1.mix(source.q,t),t);let size=settle(V(r.start.size,0.,0.),V(source.size,0.,0.),V(r.size_velocity,0.,0.),r.duration*0.64,(u/0.64).clamp(0.,1.)).0;self.pose=Pose{p:pos,q,size:size.max(1.)};
  }else{let reserve=24.+112.*self.common.lift.value.clamp(0.,1.);let base_size=(self.common.width*0.78/1.2).min((self.common.height-76.-210.-136.).max(100.)/(4./3.));let size=base_size.min((self.common.width-32.)/1.2).min((self.common.height-76.-210.-reserve).max(100.)/(4./3.));let preferred=76.+((self.common.height-210.-136.-76.-base_size*4./3.)*0.5).max(0.)+base_size*0.6;let cy=preferred.clamp(76.+size*0.6,(self.common.height-210.-reserve+BOTTOM*size).max(76.+size*0.6));let top=cy-size*0.6;let target=Pose{p:V(self.common.width/2.,top+size*0.6,-1800.),q:Q::axis(V(0.,1.,0.),0.12-self.turn.value).mul(Q::axis(V(1.,0.,0.),std::f32::consts::PI)),size};let raised=source.p.add(clearance.mul(ease(0.,0.52,p)));self.pose=Pose{p:raised.mix(target.p,ease(0.16,1.,p)),q:source.q.mix(target.q,ease(0.18,0.92,p)),size:source.size+(size-source.size)*ease(0.20,0.96,p)};}
  if dt>0.{self.velocity=self.pose.p.sub(old.p).mul(1./dt);let n=self.velocity.norm();if n>1800.{self.velocity=self.velocity.mul(1800./n);}self.size_velocity=((self.pose.size-old.size)/dt).clamp(-1000.,1000.);let mut delta=old.q.inv().mul(self.pose.q).unit();if delta.3<0.{delta=Q(-delta.0,-delta.1,-delta.2,-delta.3);}let v=V(delta.0,delta.1,delta.2);self.angular=v.unit().mul((2.*v.norm().atan2(delta.3)/dt).min(12.));self.motion_age=if self.velocity.norm()>0.01||self.angular.norm()>0.001{0.}else{self.motion_age+dt};}self.previous=old;
 }
 fn rebuild(&mut self){
  self.topology_revision=self.topology_revision%1_000_000+1;self.cache_pan=(self.pan_x.value,self.depth());
  self.cells.clear();let w=self.common.width;let h=self.common.height;
  // Inverse-project the padded viewport once. Enumerate only nearby rows/columns, keeping
  // the existing offscreen guard and prefetch ring; fixed framing does not scan the catalogue.
  let mut minx=f32::INFINITY;let mut maxx=f32::NEG_INFINITY;let mut minz=f32::INFINITY;let mut maxz=f32::NEG_INFINITY;
  let margin=self.scale()*(1.+PEAK)+32.;let center=V(w*0.5,h*0.5,0.);
  for(x,y)in [(-margin,-margin),(w+margin,-margin),(-margin,h+margin),(w+margin,h+margin)]{let(a,b)=self.ground(x-center.0,y-center.1);minx=minx.min(a-self.pan_x.value);maxx=maxx.max(a-self.pan_x.value);minz=minz.min(b-self.depth());maxz=maxz.max(b-self.depth());}
  let minc=(minx/COL).floor()as i32-1;let maxc=(maxx/COL).ceil()as i32+1;let minr=(minz/ROW).floor()as i32-3;let maxr=(maxz/ROW).ceil()as i32+3;
  let rx=self.scale()*(RIGHT.max(-LEFT)*self.view_basis[0].0.abs()+BOTTOM.abs().max(TOP)*self.view_basis[1].0.abs()+PRINT_Z*self.view_basis[2].0.abs());
  let ry=self.scale()*(RIGHT.max(-LEFT)*self.view_basis[0].1.abs()+(BOTTOM.abs().max(TOP)+PEAK)*self.view_basis[1].1.abs()+PRINT_Z*self.view_basis[2].1.abs());
  self.common.next_bindings.clear();
  if self.common.count>0{for r in minr..=maxr{for c in minc..=maxc{let cell=block_index(c,r);let pose=self.source_at(c,r,1.);let cull=self.basis(V(c as f32*COL+self.pan_x.value,0.,r as f32*ROW+self.depth())).mul(self.scale()).add(center);let gx=w*0.04+56.;let gy=h*0.04+56.;let visible=cull.0+rx>=-gx&&cull.0-rx<=w+gx&&cull.1+ry>=-gy&&cull.1-ry<=h+gy;let song=self.song(cell);self.common.next_bindings.insert(cell,song);let distance=if self.focus==Some(cell){-1.}else{(pose.p.0-w/2.).powi(2)+(pose.p.1-h*0.5).powi(2)+if visible{0.}else{1e10}};self.cells.push(Cell{cell,song,pose,distance});}}}

  if let Some(cell)=self.focus{if !self.cells.iter().any(|c|c.cell==cell)&&self.common.count>0{self.cells.push(Cell{cell,song:self.song(cell),pose:self.source(cell,1.),distance:-1.});}self.common.next_bindings.insert(cell,self.song(cell));}
  if let Some(cell)=self.common.playing{self.common.next_bindings.insert(cell,self.song(cell));}
  if self.common.count>0{self.common.next_bindings.insert(self.common.selected,self.song(self.common.selected));}
  std::mem::swap(&mut self.common.bindings,&mut self.common.next_bindings);
  let limit=(SLOTS+PREFETCH).min(self.cells.len());if self.cells.len()>limit{self.cells.select_nth_unstable_by(limit,|a,b|a.distance.total_cmp(&b.distance));self.cells.truncate(limit);}self.cells.sort_unstable_by(|a,b|a.distance.total_cmp(&b.distance).then(a.cell.cmp(&b.cell)));
  self.slots.clear();self.common.visible.clear();self.common.visible_songs.clear();self.common.prefetch_seen.clear();let mut slot=0;let mut ahead=0;self.cache.fill(0.);
  let source_matrix=Pose{p:V::default(),q:self.camera_q(),size:self.scale()}.matrix();
  for c in &self.cells{if slot<SLOTS&&c.distance<1e10{let at=HEADER+slot*STRIDE;self.cache[at]=c.song as f32;self.cache[at+1]=c.pose.p.0;self.cache[at+2]=c.pose.p.1;self.cache[at+3]=c.pose.size;self.cache[at+4]=c.pose.size;self.cache[at+5]=self.cover_emission(c.cell);self.slots.insert(c.cell,slot);self.cache[at+7]=c.cell as f32;self.cache[at+8]=1.;self.cache[at+9]=1.;self.cache[at+15]=0.;let matrix=&mut self.cache[RHINE_POSES+slot*16..RHINE_POSES+(slot+1)*16];matrix.copy_from_slice(&source_matrix);matrix[12]=c.pose.p.0;matrix[13]=c.pose.p.1;matrix[14]=c.pose.p.2;self.common.visible.push((c.cell,Rect{x:c.pose.p.0,y:c.pose.p.1,w:c.pose.size,h:c.pose.size}));self.common.visible_songs.push(c.song);self.common.prefetch_seen.insert(c.song);slot+=1;}
   else if ahead<PREFETCH&&self.common.prefetch_seen.insert(c.song){self.cache[HEADER+STRIDE*SLOTS+ahead]=c.song as f32;ahead+=1;}}
  // Rank HD independently of geometry: dense visible spines must not pin all 96
  // layers ahead of the browsed crest. Predict 350ms ahead from native camera velocity.
  let view_cell=self.view_cell();let current=block(view_cell);let focus=self.focus;let playing=self.common.playing;
  let lead=if self.browsing{V(-self.pan_x.velocity*0.35,0.,-self.pan_z.velocity*0.35)}else{V::default()};
  let length=lead.norm();let lead=if length>1.6{lead.mul(1.6/length)}else{lead};
  let future=(current.0 as f32*COL+lead.0,current.1 as f32*ROW+lead.2);
  // Geometry order has already been published. Reuse its temporary key for HD
  // priority: world-cell decoding/distances must run once per cell, not twice
  // for every sort comparison (especially with the wider Pad candidate set).
  for c in &mut self.cells{
   let rank=|c:&Cell|{if focus==Some(c.cell){return -4.}if c.cell==view_cell{return -3.}
    if playing==Some(c.cell){return -2.}
    let(x,z)=block(c.cell);let x=x as f32*COL;let z=z as f32*ROW;
    let now=(x-current.0 as f32*COL).powi(2)+((z-current.1 as f32*ROW)*0.85).powi(2);
    let next=(x-future.0).powi(2)+((z-future.1)*0.85).powi(2);
    now.min(next+0.25)};
   c.distance=rank(c);
  }
  self.cells.sort_unstable_by(|a,b|a.distance.total_cmp(&b.distance).then(a.cell.cmp(&b.cell)));
  ahead=0;self.common.prefetch_seen.clear();
  for c in &self.cells{if ahead==PREFETCH{break}if self.common.prefetch_seen.insert(c.song){self.cache[HEADER+STRIDE*SLOTS+ahead]=c.song as f32;ahead+=1;}}
  self.common.lift_slot=self.focus.and_then(|cell|self.common.visible.iter().position(|v|v.0==cell));self.cache[16]=slot as f32;self.cache[22]=ahead as f32;
 }
 pub fn frame(&mut self,dt:f32,w:f32,h:f32,out:&mut[f32])->bool{
  if out.len()<FLOATS||!w.is_finite()||!h.is_finite()||w<=0.||h<=0.{return false}self.common.width=w;self.common.height=h;let dt=if self.common.capture_hold{0.}else{dt.clamp(0.,0.1)};let old_pose=self.pose;let old_fill=self.common.fill.value;let old_blur=self.common.lift.value;let old_mode=self.common.mode.value;
  self.resume_playback_follow();let old_pan=self.applied_pan;
  let(k,d)=if self.browsing{(900.,60.)}else{(81.,18.)};
  let mut moving=self.pan_x.step_exact(dt,k,d)|self.pan_z.step_exact(dt,k,d)|self.turn.step_exact(dt,120.,22.)|self.common.mode.step(dt);
  let old_protection=self.common.playing.map(|cell|self.waves.height(cell));
  self.sync_wave();moving|=self.waves.step(dt)|self.browse_wave.step(dt);
  // Inspection poses depend on the playback crest's fading sightline protection,
  // even when their own wave has already settled. Patch those retained slots too.
  if old_protection!=self.common.playing.map(|cell|self.waves.height(cell)){self.browse_wave.mark_all_changed();}
  self.waves.changed.extend_from_slice(&self.browse_wave.changed);let wave_changed=!self.waves.changed.is_empty();let old_lift=self.view_lift;
  moving|=self.framing.step_exact(dt,256.,32.);self.view_lift=self.framing.value;self.view_lift_velocity=self.framing.velocity;
  let framing_changed=old_lift!=self.view_lift;let camera_changed=old_pan!=(self.pan_x.value,self.depth());
  let shift=self.basis(V(self.pan_x.value-old_pan.0,0.,self.depth()-old_pan.1)).mul(self.scale()).add(V(0.,-self.view_basis[1].1*self.scale()*(self.view_lift-old_lift),0.));

  if let Some(r)=&mut self.returning{r.time=(r.time+dt).min(r.duration);self.progress.value=r.progress*(1.-ease(0.,1.,r.time/r.duration));moving|=r.time<r.duration;}else if self.focus.is_some(){self.extraction_time=(self.extraction_time+dt).min(0.90);self.progress.value=self.extraction_time/0.90;moving|=self.extraction_time<0.90;}
  moving|=self.common.lift.step_lift(dt)|self.common.menu.step_with(dt,520.,32.)|self.common.confirmation.step_with(dt,420.,36.)|self.common.fill.step(dt)|self.common.lift_shadow.step(dt);
  if self.common.action_phase==1&&self.progress.value>0.8{self.common.menu.target=1.;moving|=self.common.menu.value!=1.;}
  if self.common.action_phase==2&&self.common.menu.value<0.08{self.common.confirmation.target=1.;moving|=self.common.confirmation.value!=1.;}
  self.advance_pose(dt);
  if self.returning.is_some()&&self.progress.value==0.{self.focus=None;self.returning=None;self.world_dirty=true;if let Some(next)=self.pending.take(){self.open(next);self.advance_pose(0.);moving=true;}}
  let signature=[w.to_bits(),h.to_bits(),self.pan_x.value.to_bits(),self.depth().to_bits(),self.common.count as u32];let drift=self.basis(V(self.pan_x.value-self.cache_pan.0,0.,self.depth()-self.cache_pan.1)).mul(self.scale());
  // 48dp extra culling guard covers up to 24dp of retained camera translation.
  // At capacity, rebuild normally: a truncated nearest set cannot promise that guard.
  let rebuild=self.world_dirty||signature[0..2]!=self.world_signature[0..2]||signature[4]!=self.world_signature[4]||drift.0.abs()>24.||drift.1.abs()>24.||(camera_changed&&self.cache[16]>=SLOTS as f32);
  if rebuild{self.world_signature=signature;self.rebuild();self.world_dirty=false;}else if wave_changed||framing_changed||camera_changed{self.patch_wave(shift);}if rebuild||wave_changed||framing_changed||camera_changed||old_fill!=self.common.fill.value{self.world_revision=self.world_revision%1_000_000+1;}
  let (hologram_moving,hologram_changed)=self.step_holograms(dt);moving|=hologram_moving;
  let pose_changed=old_pose.matrix()!=self.pose.matrix();
  if pose_changed {if self.focus.is_some(){
   // The moving emitter can light any visible sleeve in its forward range.
   // Include receiver extent and both endpoints so leaving the range clears it.
   let near=|p:Pose|self.cells.iter().any(|c|{let range=c.pose.size*0.95+p.size*2.4;let d=p.p.sub(c.pose.p);d.0*d.0+d.1*d.1+d.2*d.2<range*range});
   if near(old_pose)||near(self.pose){self.world_revision=self.world_revision%1_000_000+1;}
  }}if rebuild||wave_changed||framing_changed||camera_changed||pose_changed||self.dirty||old_fill!=self.common.fill.value||old_blur!=self.common.lift.value||old_mode!=self.common.mode.value||hologram_changed{self.revision=self.revision%1_000_000+1;}
  out[..FLOATS].copy_from_slice(&self.cache);out[0]=if moving&&!self.common.capture_hold{1.}else{0.};out[1]=self.common.mode.value.clamp(0.,1.);out[4]=1.;out[5]=900.;out[6]=1100.;out[7]=w/2.;out[8]=h*0.5;out[9]=w;out[10]=h;out[11]=self.song(self.common.selected)as f32;out[15]=1.;out[18]=1.-out[1];out[19]=-20.*out[1];out[20]=self.common.header;out[21]=self.common.scroll;out[23]=self.revision as f32;out[24]=if moving{1.}else{0.};out[25]=if pose_changed{1.}else{0.};let [top,bottom,strength]=crate::render_policy::screen_edges(w,h);out[26]=top;out[27]=bottom;out[28]=100.;out[29]=14.;out[30]=strength;out[RHINE]=1.;out[RHINE+1]=self.progress.value;out[RHINE+2]=self.world_revision as f32;out[RHINE+4]=YAW.to_radians();out[RHINE+5]=ARCHIVE_MATERIAL;out[RHINE+6]=5.;out[RHINE+7]=self.topology_revision as f32;
  // Nearby soft lamp, in the same unpadded display-space coordinates as every pose.
  // Keep the source fixed while cards and the camera move beneath it. w is source radius.
  let lamp_scale=w.max(h);out[RHINE+8]=w*0.5;out[RHINE+9]=h*0.5;out[RHINE+10]=-lamp_scale*1.5;out[RHINE+11]=lamp_scale*0.38;out[RHINE+12]=if self.manual_view{1.}else{0.};out[RHINE+13]=THICK;out[RHINE+14]=RIM_TRANSMISSION;out[RHINE+15]=self.caption_reference_size()*0.92;out[RHINE+16]=self.caption_reference_size()*0.19;out[RHINE+17]=0.;
  out[17]=if self.common.count>0&&self.cells.iter().any(|c|c.song+384>=self.common.count){1.}else{0.};
  for n in 0..out[16]as usize{let o=HEADER+n*STRIDE;let cell=out[o+7]as usize;if self.common.hidden_cell==Some(cell){out[o+9]=0.;}else if self.common.fill_cell==Some(cell){out[o+9]=self.common.fill.value.clamp(0.,1.);}}
  if self.common.fill.value==1.{self.common.fill_cell=None;}
  self.write_holograms(out);self.write_context(out);self.applied_pan=(self.pan_x.value,self.depth());self.dirty=false;true
 }
 fn step_holograms(&mut self,dt:f32)->(bool,bool){
  let before=self.holograms.map(|h|(h.cell,h.alpha.value));
  let available=self.common.count>0&&self.focus.is_none()&&self.common.action_phase==0;
  let playing=self.common.playing.filter(|_|available&&!self.browsing&&self.common.playing_song.is_some());
  let inspected=if available&&self.manual_view{Some(self.view_cell())}else{None};
  let targets=[playing,inspected.filter(|c|Some(*c)!=self.common.playing)];
  for h in &mut self.holograms{h.alpha.target=if h.cell.is_some()&&targets.contains(&h.cell){1.}else{0.};}
  for cell in targets.into_iter().flatten(){
   if self.holograms.iter().any(|h|h.cell==Some(cell)){continue}
   // Retain outgoing identities during their return. Under rapid browsing only
   // the faintest outgoing slot is reused; active playback/inspection cannot be stolen.
   if let Some(slot)=(0..HOLOGRAM_SLOTS).filter(|&i|self.holograms[i].alpha.target==0.)
    .min_by(|&a,&b|self.holograms[a].alpha.value.total_cmp(&self.holograms[b].alpha.value)){
    self.holograms[slot]=Hologram{cell:Some(cell),alpha:Spring::new(0.)};self.holograms[slot].alpha.target=1.;
   }
  }
  let mut moving=false;
  for h in &mut self.holograms{moving|=h.alpha.step_exact(dt,196.,28.);if h.alpha.value==0.&&h.alpha.target==0.{h.cell=None;}}
  (moving,before!=self.holograms.map(|h|(h.cell,h.alpha.value)))
 }
 fn write_holograms(&self,out:&mut[f32]){
  let start=RHINE+RHINE_HOLOGRAM;out[start..start+HOLOGRAM_STRIDE*HOLOGRAM_SLOTS].fill(0.);
  let w=self.common.width;let h=self.common.height;
  for (i,holo) in self.holograms.iter().enumerate(){
   let Some(cell)=holo.cell else{continue};let Some(&slot)=self.slots.get(&cell)else{continue};
   let record=HEADER+slot*STRIDE;if out[record+9]<=0.{continue}
   // The whole label/button plane uses the same right/up axes as the sleeve.
   // Do not clamp its anchor independently: that detaches it from the 3D scene.
   let m=&out[RHINE_POSES+slot*16..RHINE_POSES+(slot+1)*16];
   let alpha=holo.alpha.value.clamp(0.,1.)*ease(0.12,0.8,self.cover_emission(cell))*out[record+9];
   let [mut xx,mut xy,mut yx,mut yy,mut x,mut y]=hologram_plane(m,alpha);
   let background=1.-0.055*self.progress.value.clamp(0.,1.);
   x=(x-w*0.5)*background+w*0.5;y=(y-h*0.5)*background+h*0.5;
   xx*=background;xy*=background;yx*=background;yy*=background;
   let at=start+i*HOLOGRAM_STRIDE;let song=out[record];
   out[at..at+HOLOGRAM_STRIDE].copy_from_slice(&[cell as f32,song,x,y,HOLO_WIDTH,HOLO_HEIGHT,alpha,xx,
    if holo.alpha.target==1.&&alpha>0.6&&self.common.action_phase==0&&self.focus.is_none(){1.}else{0.},xy,yx,yy]);
   let bounds=hologram_bounds(&out[at..at+HOLOGRAM_STRIDE]);
   let visibility=ease(0.,24.,bounds[2])*ease(0.,24.,w-bounds[0])
    *ease(0.,24.,bounds[3])*ease(0.,24.,h-bounds[1]);out[at+6]*=visibility;

  }
  // Adjacent old/new crests can project to the same place. Retire the outgoing
  // lettering under the arriving label instead of accumulating readable titles.
  for i in 0..HOLOGRAM_SLOTS{let a=start+i*HOLOGRAM_STRIDE;if out[a+6]<=0.{continue}
   let mut mask=1.;
   for j in 0..HOLOGRAM_SLOTS{if i==j{continue}let b=start+j*HOLOGRAM_STRIDE;
    let priority=|n:usize|(self.holograms[n].alpha.target as i32,self.holograms[n].cell==Some(self.view_cell()),n);
    if priority(j)<=priority(i){continue}
    let ra=hologram_bounds(&out[a..a+HOLOGRAM_STRIDE]);let rb=hologram_bounds(&out[b..b+HOLOGRAM_STRIDE]);
    let dx=ra[2].min(rb[2])-ra[0].max(rb[0]);let dy=ra[3].min(rb[3])-ra[1].max(rb[1]);
    let overlap=dx.max(0.)*dy.max(0.)/((ra[2]-ra[0])*(ra[3]-ra[1])).max(1.);
    mask*=1.-ease(0.,0.35,overlap)*ease(0.,0.6,out[b+6]);
   }
   out[a+6]*=mask;if out[a+6]<=0.6{out[a+8]=0.;}
  }
 }
 fn write_context(&self,out:&mut[f32]){
  let u=&mut out[ACTION..ACTION+192];u.fill(0.);u[3]=self.common.action_phase as f32;u[4]=-1.;u[5]=-1.;u[23]=-1.;u[20]=28.;u[25]=0.94+0.06*self.common.confirmation.value;u[124]=self.world_revision as f32;u[125]=VERSION as f32;u[127]=1.-0.055*self.progress.value.clamp(0.,1.);u[0]=self.common.menu.value.clamp(0.,1.);u[1]=self.common.lift.value.clamp(0.,1.);u[2]=self.common.confirmation.value.clamp(0.,1.);u[26]=if self.common.capture_hold{1.}else{0.};u[22]=self.common.playing.map(|c|c as f32).unwrap_or(-1.);
  u[PLAYING_OPEN_PROGRESS]=self.common.playing.map_or(0.,|cell|(self.waves.height(cell)/PEAK).clamp(0.,1.));
  let(Some(cell),Some(slot))=(self.focus,self.common.lift_slot)else{return};u[4]=cell as f32;u[5]=self.song(cell)as f32;u[23]=slot as f32;u[27]=1.;u[24]=self.progress.value;u[126]=self.progress.value.clamp(0.,1.)*0.5*if self.common.action_phase==3{self.common.lift_shadow.value.clamp(0.,1.)}else{1.};
  u[6]=f32::INFINITY;u[7]=f32::INFINITY;u[8]=f32::NEG_INFINITY;u[9]=f32::NEG_INFINITY;
  let mut points=[V::default();8];let mut at=0;for x in [LEFT,RIGHT]{for y in [BOTTOM,TOP]{for z in [-PRINT_Z,PRINT_Z]{points[at]=self.pose.point(V(x,y,z));at+=1;}}}
  points.sort_unstable_by(|a,b|a.0.total_cmp(&b.0).then(a.1.total_cmp(&b.1)));
  let cross=|a:V,b:V,c:V|(b.0-a.0)*(c.1-a.1)-(b.1-a.1)*(c.0-a.0);let mut hull=[V::default();16];let mut len=0;
  for point in points{while len>=2&&cross(hull[len-2],hull[len-1],point)<=0.{len-=1;}hull[len]=point;len+=1;}
  let lower=len+1;for point in points[..7].iter().rev(){while len>=lower&&cross(hull[len-2],hull[len-1],*point)<=0.{len-=1;}hull[len]=*point;len+=1;}len=len.saturating_sub(1);
  let mut total=0.;let mut lengths=[0.;16];for n in 0..len{let a=hull[n];let b=hull[(n+1)%len];lengths[n]=(b.0-a.0).hypot(b.1-a.1);total+=lengths[n];u[6]=u[6].min(a.0);u[7]=u[7].min(a.1);u[8]=u[8].max(a.0);u[9]=u[9].max(a.1);}
  for n in 0..32{let mut distance=total*n as f32/32.;let mut edge=0;while edge+1<len&&distance>lengths[edge]{distance-=lengths[edge];edge+=1;}let p=hull[edge].mix(hull[(edge+1)%len],distance/lengths[edge].max(1e-6));u[32+n*2]=p.0;u[33+n*2]=p.1;}
  u[28]=u[6];u[29]=u[7];u[30]=u[8]-u[6];u[31]=u[9]-u[7];
  let tray=Rect{x:(self.common.width-260.).max(16.)/2.,y:(u[9]+14.).min(self.common.height-210.-120.),w:260_f32.min(self.common.width-32.),h:120.};let slide=16.*(1.-self.common.menu.value);let quick=(tray.w-12.)/3.;
  for n in 0..4{let r=if n<3{Rect{x:tray.x+n as f32*(quick+6.),y:tray.y+slide,w:quick,h:66.}}else{Rect{x:tray.x,y:tray.y+72.+slide,w:tray.w,h:48.}};let a=96+n*4;u[a..a+4].copy_from_slice(&[r.x,r.y,r.w,r.h]);u[10+n*2]=r.x+r.w/2.;u[11+n*2]=r.y+r.h/2.;u[112+n]=1.;u[116+n]=u[0];u[120+n]=4.;for(k,(x,y))in[(r.x,r.y),(r.x+r.w,r.y),(r.x+r.w,r.y+r.h),(r.x,r.y+r.h)].iter().enumerate(){u[128+n*16+k*2]=*x;u[129+n*16+k*2]=*y;}}
  u[18]=self.common.width/2.;u[19]=tray.y+24.;
  let o=HEADER+slot*STRIDE;out[RHINE_POSES+slot*16..RHINE_POSES+(slot+1)*16].copy_from_slice(&self.pose.matrix());let a=self.pose.point(V(-0.46,-0.52,THICK/2.));let b=self.pose.point(V(0.46,-0.71,THICK/2.));let front=(-self.pose.q.rotate(V(0.,0.,1.)).2).max(0.);out[o+5]=out[o+5].max(ease(0.,0.8,self.progress.value));out[o+6]=ease(0.65,0.95,self.progress.value)*front;out[o+10]=a.0.min(b.0);out[o+11]=a.1.min(b.1);out[o+12]=(a.0-b.0).abs();out[o+13]=(a.1-b.1).abs();out[o+14]=self.pose.size/self.caption_reference_size();out[o+3]=self.pose.size;out[o+4]=self.pose.size;out[31]=self.pose.size*1.333;
 }
}

#[cfg(test)]mod tests{
 use super::*;
 fn ready()->(Rhine,Vec<f32>){let mut r=Rhine::new();let mut f=vec![0.;FLOATS];r.command(0,1024,0.,0.);r.common.mode.jump(1.);r.frame(0.,400.,890.,&mut f);(r,f)}
 fn cover_overlap(r:&Rhine,f:&[f32],cell:usize)->f32{
  let pose=r.source(cell,1.);let mut records=Vec::new();
  for n in 0..f[16]as usize{let mut matrix=[0.;16];matrix.copy_from_slice(&f[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);records.push(rhine_gpu::HitRecord{cell:f[HEADER+n*STRIDE+7]as usize,matrix,alpha:f[HEADER+n*STRIDE+9],owner:false});}
  let mut hidden=0;
  // Uniform samples on the actual orthographic print plane have equal screen
  // area; ray-test all published sleeves, not a height-parameter approximation.
  for x in 0..24{for y in 0..64{let p=pose.point(V((x as f32+0.5)/24.-0.5,(y as f32+0.5)/64.-0.5,PRINT_Z));
   if rhine_gpu::hit(&records,p.0,p.1,(200.,445.),1.)!=cell as i32{hidden+=1;}
  }}hidden as f32/(24.*64.)
 }
 #[test]fn camera_fits_pad_height_without_magnifying_cards_with_landscape_width(){
  for(w,h)in[(400.,890.),(412.,915.),(800.,1200.),(1200.,800.),(1280.,800.),(960.,720.),(840.,800.)]{
   let mut r=Rhine::new();let mut f=vec![0.;FLOATS];r.common.mode.jump(1.);r.frame(0.,w,h,&mut f);r.command(10,1024,0.,0.);
   for _ in 0..180{r.frame(1./90.,w,h,&mut f);}
   if h>=2.*w{assert_eq!(r.scale(),w/CAMERA_WIDTH,"retain tall-phone scale bit for bit");}
   assert!(f[16]<SLOTS as f32,"Pad viewport must retain the full camera guard");
   let pose=r.source(0,1.);let mut lo=f32::INFINITY;let mut hi=f32::NEG_INFINITY;
   for x in [LEFT,RIGHT]{for y in [BOTTOM,TOP]{for z in [-PRINT_Z,PRINT_Z]{let p=pose.point(V(x,y,z));lo=lo.min(p.1);hi=hi.max(p.1);}}}
   assert!(hi-lo<h*0.46&&lo>0.&&hi<h,"card exceeds vertical framing at {w}x{h}: {lo}..{hi}");
   let(x,z)=r.ground(70.,-38.);let p=r.basis(V(x,0.,z)).mul(r.scale());assert!((p.0-70.).abs()<0.002&&(p.1+38.).abs()<0.002);
   let mut published=Vec::new();for n in 0..f[16]as usize{let mut matrix=[0.;16];matrix.copy_from_slice(&f[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);published.push(rhine_gpu::HitRecord{cell:f[HEADER+n*STRIDE+7]as usize,matrix,alpha:1.,owner:false});}
   // The wider field must not silently leave visible edge cards out of the
   // bounded frame. Compare sampled first-hit surfaces with the full candidate grid.
   let reference:Vec<_>=r.common.bindings.keys().map(|&cell|rhine_gpu::HitRecord{cell,matrix:r.source(cell,1.).matrix(),alpha:1.,owner:false}).collect();
   for ix in 0..9{for iy in 0..11{let x=w*ix as f32/8.;let y=h*iy as f32/10.;
    let full=rhine_gpu::hit(&reference,x,y,(w/2.,h/2.),1.);let bounded=rhine_gpu::hit(&published,x,y,(w/2.,h/2.),1.);
    assert_eq!(bounded,full,"incomplete Pad field at {w}x{h}, sample {x},{y}");
   }}
   println!("Viewport {w}x{h} dp: scale {:.2} -> {:.2}, projected sleeve height {:.1}%, published {} instances",w/CAMERA_WIDTH,r.scale(),(hi-lo)/h*100.,f[16]);
  }
 }
 #[test]fn explicit_rhine_style_resizes_through_public_ingress_and_keeps_picking(){
  let id=create();let mut f=vec![0.;FLOATS];assert!(command(id,30,1,0.,0.));assert!(command(id,10,1024,0.,0.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./90.,400.,890.,&mut f));}
  for(w,h)in[(1200.,800.),(800.,1200.),(800.,640.),(400.,890.)]{
   assert!(step(id,0.,w,h,&mut f));assert_eq!(f[RHINE],1.);assert_eq!(f[ACTION+22],0.);
   let state=entry(id).unwrap();let runtime=state.scene.lock().unwrap();let r=&runtime.portrait;
   assert_eq!(r.common.count,1024);assert_eq!((r.pan_x.value,r.pan_z.value),(0.,0.));
   let p=r.source(0,1.).point(V(0.,0.25,PRINT_Z));drop(runtime);
   assert_eq!(hit(id,p.0,p.1),0,"published hit snapshot must use the resized camera");
   assert_eq!(f[0],0.,"resize must not start an idle render loop");
  }release(id);
 }
 #[test]fn unplayed_inspection_centres_its_crest_on_phone_and_pad(){
  for(w,h)in[(400.,890.),(1200.,800.)]{
   let mut r=Rhine::new();let mut f=vec![0.;FLOATS];r.common.mode.jump(1.);r.frame(0.,w,h,&mut f);r.command(10,1024,0.,-1.);
   assert_eq!(r.framing.value,0.);assert!(r.command(26,0,0.,0.));
   assert_eq!(r.framing.value,0.,"starting inspection must ease, not jump");
   r.command(27,0,24.,0.);for _ in 0..180{r.frame(1./90.,w,h,&mut f);}
   let p=r.source(r.view_cell(),1.).p;assert!((p.1-h*0.5).abs()<h*0.12,"unplayed inspection at {w}x{h}: y={}",p.1);
   assert!(r.common.playing.is_none());r.command(28,0,0.,0.);for _ in 0..180{r.frame(1./90.,w,h,&mut f);}
   assert_eq!(f[0],0.);assert_eq!(r.framing.value,PEAK);assert!(r.common.playing.is_none());
  }
 }
 #[test]fn continuous_archive_pose_picking_and_exact_dock(){let(mut r,mut f)=ready();let before=r.source(0,1.).matrix();r.command(15,0,0.,0.);for _ in 0..200{r.frame(1./120.,400.,890.,&mut f);}let slot=f[ACTION+23]as usize;assert_eq!(f[HEADER+slot*STRIDE+7],0.);assert_eq!(f[RHINE],1.);let matrix=r.pose.matrix();let record=rhine_gpu::HitRecord{cell:0,matrix,alpha:1.,owner:true};assert_eq!(rhine_gpu::hit(&[record],matrix[12],matrix[13],(200.,427.2),1.),0);r.turn.jump(2.8);r.frame(1./120.,400.,890.,&mut f);r.command(23,0,0.,0.);for _ in 0..200{r.frame(1./120.,400.,890.,&mut f);}assert!(r.focus.is_none());for(a,b)in before.iter().zip(r.pose.matrix()){assert!((a-b).abs()<0.001,"dock pose mismatch {a} {b}");}}
 #[test]fn compact_rows_repeat_small_catalogues(){let(mut r,mut f)=ready();r.command(0,3,0.,0.);r.frame(0.,400.,890.,&mut f);assert!(f[16]>10.&&f[16]<=SLOTS as f32);for n in 0..f[16]as usize{assert!((0.0..3.0).contains(&f[HEADER+n*STRIDE]));}}
 #[test]fn extraction_retains_geometry_and_stops_relighting_after_source_leaves(){
  let(mut r,mut f)=ready();r.command(15,0,0.,0.);r.frame(0.,400.,890.,&mut f);
  let topology=r.topology_revision;let poses:Vec<_>=r.cells.iter().map(|c|(c.cell,c.pose.matrix())).collect();let mut light_updates=0;
  for _ in 0..180{let before=r.world_revision;r.frame(1./120.,400.,890.,&mut f);
   assert_eq!(r.topology_revision,topology);assert_eq!(poses,r.cells.iter().map(|c|(c.cell,c.pose.matrix())).collect::<Vec<_>>());
   if before!=r.world_revision{light_updates+=1;}
  }
  assert!(light_updates>0&&light_updates<180);let settled=r.world_revision;r.frame(1./120.,400.,890.,&mut f);assert_eq!(r.world_revision,settled);assert_eq!(r.progress.value,1.);assert!(!r.frame(0.,0.,890.,&mut f));
 }
 #[test]fn capture_hold_and_dislike_hide_source_without_locking_map(){let(mut r,mut f)=ready();r.command(15,0,0.,0.);for _ in 0..200{r.frame(1./120.,400.,890.,&mut f);}r.command(17,0,0.,0.);r.command(21,0,0.,0.);let pose=r.pose.matrix();for _ in 0..3{r.frame(0.1,400.,890.,&mut f);assert_eq!(r.pose.matrix(),pose);assert_eq!(f[0],0.);}r.command(18,0,0.,0.);r.frame(1./120.,400.,890.,&mut f);let slot=f[ACTION+23]as usize;assert_eq!(f[HEADER+slot*STRIDE+9],0.);r.command(20,0,0.,0.);r.frame(1./120.,400.,890.,&mut f);assert!(r.focus.is_none());assert_eq!(r.common.count,1023);assert!(!r.common.capture_hold);assert_eq!(r.common.action_phase,0);let cell=r.common.visible[0].0;assert!(r.command(15,cell as i32,0.,0.));assert_eq!(r.focus,Some(cell));}
 #[test]fn frame_gpu_packing_matches_rust_pose(){let(mut r,mut f)=ready();r.command(15,0,0.,0.);r.frame(0.1,400.,890.,&mut f);let mut binding=vec![0;SLOTS*5];for n in 0..f[16]as usize{binding[n*5]=n as i32;binding[n*5+1]=1;}let mut fg=[0.;rhine_gpu::INSTANCE];assert_eq!(rhine_gpu::pack(&f,&binding,&mut fg,true),Some(1));let slot=f[ACTION+23]as usize;assert_eq!(&fg[..16],&f[RHINE_POSES+slot*16..RHINE_POSES+(slot+1)*16]);let mut bg=vec![0.;SLOTS*rhine_gpu::INSTANCE];assert_eq!(rhine_gpu::pack(&f,&binding,&mut bg,false),Some(f[16]as usize-1));}
 #[test]fn refill_refreshes_cached_background_and_menu_fits(){
  let(mut r,mut f)=ready();r.command(15,0,0.,0.);for _ in 0..220{r.frame(1./120.,683.,1024.,&mut f);}r.frame(1./120.,683.,1024.,&mut f);assert!(f[ACTION+9]+13.<=f[ACTION+97],"menu overlaps sleeve");
  r.command(17,0,0.,0.);r.command(18,0,0.,0.);r.command(20,0,0.,0.);r.frame(1./120.,683.,1024.,&mut f);let version=r.world_revision;r.frame(1./120.,683.,1024.,&mut f);assert!(r.world_revision!=version,"refill reused a fully transparent cached image");
  let mut a=Spring::new(0.);a.target=1.;let mut b=a;a.step_exact(0.1,90.,18.);for _ in 0..12{b.step_exact(1./120.,90.,18.);}assert!((a.value-b.value).abs()<1e-5);
  for axis in [V(1.,0.,0.),V(0.,1.,0.),V(0.,0.,1.)]{assert!(r.basis(axis).sub(r.camera_q().rotate(axis)).norm()<1e-5);}
 }



 #[test]fn playback_wave_stays_with_its_cell_and_sleeps(){
  let(mut r,mut f)=ready();assert!(r.waves.is_empty());r.command(1,0,0.,0.);assert!(r.focus.is_none());
  r.command(2,0,1.,0.);for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}
  let peak=r.common.playing.unwrap();assert_eq!(r.wave_height(peak),PEAK);let(c,z)=block(peak);
  assert!((r.wave_height(block_index(c,z+1))-r.wave_height(block_index(c,z-1))).abs()<0.01);
  assert!(r.wave_height(block_index(c+1,z))>0.);assert_eq!(r.wave_height(block_index(c+4,z)),ambient(block_index(c+4,z)));
  let heights:Vec<_>=(0..100).map(|cell|r.wave_height(cell)).collect();
  r.pan_x.jump(1.2);r.pan_z.jump(0.9);r.frame(0.,400.,890.,&mut f);
  assert_eq!(heights,(0..100).map(|cell|r.wave_height(cell)).collect::<Vec<_>>());
  assert_eq!(f[0],0.);let version=r.world_revision;r.frame(0.1,400.,890.,&mut f);assert_eq!(version,r.world_revision);
  r.command(14,0,0.,0.);assert_eq!(r.pan_x.target,-(c as f32)*COL);assert_eq!(r.pan_z.target,-(z as f32)*ROW);
 }
 #[test]fn track_changes_retarget_wave_without_jump_and_missing_playback_retracts(){
  let(mut r,mut f)=ready();r.command(2,0,1.,0.);for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}
  let old=r.wave_height(0);r.command(2,80,1.,0.);assert_eq!(r.wave_height(0),old);
  let next=r.common.playing.unwrap();assert_eq!(r.song(next),80);
  for _ in 0..12{r.frame(1./120.,400.,890.,&mut f);}assert!(r.wave_height(0)<old);assert!(r.wave_height(next)>0.);
  let value=r.wave_height(next);r.command(2,120,1.,0.);assert_eq!(r.wave_height(next),value);
  for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}assert_eq!(r.wave_height(0),ambient(0));
  let active=r.common.playing.unwrap();assert_eq!(r.wave_height(active),PEAK);
  r.command(20,120,0.,0.);for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}
  assert!(r.common.playing.is_none());assert!(r.waves.is_empty());assert!(!r.command(14,0,0.,0.));assert_eq!(f[0],0.);
 }
 #[test]fn compact_multiple_rows_have_clearance_and_retain_playing_identity(){
  let(mut r,mut f)=ready();assert!((COL-(RIGHT-LEFT)-0.5/30.).abs()<1e-6);assert!((ROW-2.*PRINT_Z-1.1/30.).abs()<1e-6);assert!(f[16]<SLOTS as f32);
  let mut columns=std::collections::HashSet::new();let mut rows=std::collections::HashSet::new();
  for n in 0..f[16]as usize{let cell=f[HEADER+n*STRIDE+7]as usize;let(c,z)=block(cell);assert_eq!(block_index(c,z),cell);columns.insert(c);rows.insert(z);
   let at=RHINE_POSES+n*16;let point=V(f[at+12],f[at+13],f[at+14]).sub(r.center()).mul(1./r.scale());let local=r.camera_q().inv().rotate(point);assert!((local.0-c as f32*COL).abs()<1e-4);assert!((local.2-z as f32*ROW).abs()<1e-4);
  }assert!(columns.len()>=3&&rows.len()>=3);
  r.command(10,1024,0.,900.);r.frame(0.,400.,890.,&mut f);let cell=r.common.playing.unwrap();assert_eq!(r.song(cell),900);assert!(r.common.visible.iter().any(|c|c.0==cell));
 }
 #[test]fn fixed_near_camera_rejects_all_manual_motion_but_keeps_actions(){
  let(mut r,mut f)=ready();let before=r.source(0,1.).matrix();let revision=r.world_revision;
  for op in [4,5,6,11,12,13,24,25]{assert!(!r.command(op,0,80.,30.));}
  r.frame(0.1,400.,890.,&mut f);assert_eq!(before,r.source(0,1.).matrix());assert_eq!(revision,r.world_revision);assert_eq!(f[0],0.);
  assert!(r.scale()>400./2.25);assert!(r.basis(V(0.,1.,0.)).2.abs()<0.5);
  let(x,z)=r.ground(70.,-38.);let screen=r.basis(V(x,0.,z)).mul(r.scale());assert!((screen.0-70.).abs()<0.002&&(screen.1+38.).abs()<0.002);
  let hit=rhine_gpu::HitRecord{cell:0,matrix:before,alpha:1.,owner:false};assert_eq!(rhine_gpu::hit(&[hit],before[12],before[13],(200.,445.),1.),0);
  assert!(r.command(1,0,0.,0.));assert!(r.command(15,0,0.,0.));assert_eq!(r.focus,Some(0));
 }
 #[test]fn wave_only_frames_patch_coherent_poses_without_rebuilding_candidates(){
  let(mut r,mut f)=ready();r.command(10,1024,0.,0.);r.frame(0.,400.,890.,&mut f);
  let candidates:Vec<_>=r.cells.iter().map(|c|(c.cell,c.pose.matrix())).collect();let before=r.world_revision;
  for _ in 0..10{r.frame(1./120.,400.,890.,&mut f);}
  assert_ne!(r.world_revision,before);assert!(candidates==r.cells.iter().map(|c|(c.cell,c.pose.matrix())).collect::<Vec<_>>(),"wave-only frame rebuilt candidates");
  assert!(r.wave_height(0)>0.);assert_eq!(f[RHINE+5],4.);assert_eq!(f[RHINE+13],THICK);assert_eq!(f[RHINE+14],0.02);
  for slot in 0..f[16]as usize{let cell=f[HEADER+slot*STRIDE+7]as usize;let m=r.source(cell,1.).matrix();let at=RHINE_POSES+slot*16;for n in 12..15{assert!((m[n]-f[at+n]).abs()<0.001);}}
  assert!((r.basis(V(0.,1.,0.)).2.abs()-17.5_f32.to_radians().sin()).abs()<0.0001);
 }

 #[test]fn playback_follows_loosely_without_locking_to_height_or_freezing(){
  let(mut r,mut f)=ready();r.command(10,1024,0.,0.);r.frame(0.,400.,890.,&mut f);let pan=(r.pan_x.value,r.pan_z.value);
  for song in [1,2]{r.command(2,song,1.,0.);for _ in 0..120{r.frame(1./120.,400.,890.,&mut f);assert_eq!((r.pan_x.value,r.pan_z.value),pan);}}
  let lift=r.view_lift;let value=(r.pan_x.value,r.pan_z.value);let speed=(r.pan_x.velocity,r.pan_z.velocity);
  r.command(2,12,1.,0.);assert_eq!((r.pan_x.value,r.pan_z.value),value);assert_eq!((r.pan_x.velocity,r.pan_z.velocity),speed);
  let target=(r.pan_x.target,r.pan_z.target);assert_ne!(target,pan);
  for _ in 0..300{r.frame(1./120.,400.,890.,&mut f);assert_eq!((r.pan_x.target,r.pan_z.target),target);assert_eq!(r.view_lift,lift);}
  let p=r.source(r.common.playing.unwrap(),1.).p;assert!((p.0-200.).abs()>10.);assert!((p.0-200.).abs()<=48.01);assert!((p.1-445.).abs()<=89.01);
  r.command(2,8,1.,0.);r.frame(0.04,400.,890.,&mut f);let value=(r.pan_x.value,r.pan_z.value);let velocity=(r.pan_x.velocity,r.pan_z.velocity);
  r.command(2,14,1.,0.);assert_eq!((r.pan_x.value,r.pan_z.value),value);assert_eq!((r.pan_x.velocity,r.pan_z.velocity),velocity);
  r.command(14,0,0.,0.);for _ in 0..300{r.frame(1./120.,400.,890.,&mut f);}let p=r.source(r.common.playing.unwrap(),1.).p;
  assert!((p.0-200.).abs()<0.01&&(p.1-445.).abs()<0.01);assert_eq!(f[0],0.);
 }

 #[test]fn playing_cover_keeps_35_to_40_percent_overlap_at_the_actual_camera_angle(){
  let(mut r,mut f)=ready();
  for song in [0,80,120]{r.command(2,song,1.,0.);r.command(14,0,0.,0.);for _ in 0..260{r.frame(1./120.,400.,890.,&mut f);}
   let cell=r.common.playing.unwrap();let pose=r.source(cell,1.);let mut records=Vec::new();
   for n in 0..f[16]as usize{let mut matrix=[0.;16];matrix.copy_from_slice(&f[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);records.push(rhine_gpu::HitRecord{cell:f[HEADER+n*STRIDE+7]as usize,matrix,alpha:f[HEADER+n*STRIDE+9],owner:false});}
   for x in [-0.46,-0.23,0.,0.23,0.46]{for y in [-0.1,0.,0.23,0.46]{let point=pose.point(V(x,y,PRINT_Z));assert_eq!(rhine_gpu::hit(&records,point.0,point.1,(200.,445.),1.),cell as i32,"upper 60% of playing cover occluded at {x},{y}");}}
   let overlap=cover_overlap(&r,&f,cell);println!("Playing song {song}: projected cover overlap {:.2}%",overlap*100.);
   assert!((0.35..=0.40).contains(&overlap),"playing cover overlap {overlap}");
  }
 }

 #[test]fn small_camera_motion_reuses_topology_and_keeps_exact_projected_poses(){
  let(mut r,mut f)=ready();r.frame(0.,400.,890.,&mut f);let topology=r.topology_revision;
  for _ in 0..12{r.pan_z.value+=0.002;r.pan_z.target=r.pan_z.value;r.frame(0.,400.,890.,&mut f);
   assert_eq!(r.topology_revision,topology);
   for n in 0..f[16]as usize{let cell=f[HEADER+n*STRIDE+7]as usize;let p=r.source(cell,1.).p;let m=RHINE_POSES+n*16;assert!((f[m+12]-p.0).abs()<0.002);assert!((f[m+13]-p.1).abs()<0.002);assert!((f[m+14]-p.2).abs()<0.002);}
  }
  r.pan_z.value+=1.;r.pan_z.target=r.pan_z.value;r.frame(0.,400.,890.,&mut f);assert!(r.topology_revision>topology);
 }

 #[test]fn playback_camera_rebuilds_only_when_its_guard_is_consumed(){
  let(mut cached,mut a)=ready();let(mut reference,mut b)=ready();cached.command(2,1,1.,0.);reference.command(2,1,1.,0.);cached.command(14,0,0.,0.);reference.command(14,0,0.,0.);
  let before=cached.topology_revision;let mut moving_frames=0;
  for _ in 0..160{let pan=(cached.pan_x.value,cached.pan_z.value);cached.frame(1./120.,400.,890.,&mut a);reference.world_dirty=true;reference.frame(1./120.,400.,890.,&mut b);
   if pan!=(cached.pan_x.value,cached.pan_z.value){moving_frames+=1;}
   let cell=cached.common.playing.unwrap();let p=cached.source(cell,1.).p;let q=reference.source(cell,1.).p;assert!(p.sub(q).norm()<0.001);
  }
  let rebuilds=cached.topology_revision-before;assert!(rebuilds<moving_frames/2,"{rebuilds} rebuilds for {moving_frames} moving frames");
  println!("Rhine camera: {rebuilds} candidate rebuilds / {moving_frames} moving frames (old path rebuilt each moving frame)");
 }

 #[test]fn playback_card_is_separate_from_the_rounded_neighbouring_hill(){
  let(mut r,mut f)=ready();
  for song in [0,80,120]{r.command(2,song,1.,0.);for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}
   let(x,z)=block(r.common.playing.unwrap());assert_eq!(r.wave_height(block_index(x,z)),PEAK);
   assert!((0.48..0.56).contains(&r.wave_height(block_index(x,z-1))),"shoulders must rise visibly above the ambient field");assert!((r.wave_height(block_index(x,z-1))-r.wave_height(block_index(x,z+1))).abs()<0.01);
   for dz in 1..10{assert!(r.wave_height(block_index(x,z+dz+1))<=r.wave_height(block_index(x,z+dz))+0.00001,"foreground re-rises at {dz}");}
  }
 }

 #[test]fn browsing_a_repeated_copy_does_not_redirect_sequential_playback(){
  let(mut r,mut f)=ready();r.command(10,1024,0.,0.);r.frame(0.,400.,890.,&mut f);
  let copy=block_index(1,-96);assert_eq!(r.song(copy),1);
  r.command(1,copy as i32,0.,0.);r.command(2,1,1.,0.);
  assert_eq!(r.common.playing,Some(block_index(0,1)));
  r.command(2,0,1.,0.);r.command(1,copy as i32,0.,0.);r.command(29,1,0.,0.);r.command(2,1,1.,0.);
  assert_eq!(r.common.playing,Some(copy));
 }

 #[test]fn sequential_playback_stays_in_the_wave_lane_including_wrap_and_reverse(){
  let(mut r,mut f)=ready();r.command(10,1024,1022.,1022.);r.frame(0.,400.,890.,&mut f);
  let mut previous=r.common.playing.unwrap();let column=block(previous).0;
  for (song,direction) in [(1023,1),(0,1),(1,1),(0,-1),(1023,-1)]{
   let row=block(previous).1;let expected=block_index(column,row+direction);
   assert_eq!(r.song(expected),song as usize,"next cover must already be in its slot");
   let pan=(r.pan_x.value,r.pan_z.value);r.command(2,song,1.,0.);
   assert_eq!(r.common.playing,Some(expected));assert_eq!((r.pan_x.value,r.pan_z.value),pan);
   // Wider front/back spacing can cross the loose-follow band sooner. That may
   // retarget the camera, but must neither jump its pose nor switch the song lane.
   let(c,z)=block(expected);let offset=r.basis(V(c as f32*COL+r.pan_x.target,PEAK-r.framing.target,z as f32*ROW+r.pan_z.target)).mul(r.scale());
   assert!(offset.0.abs()<=48.01&&offset.1.abs()<=89.01);
   r.frame(1./120.,400.,890.,&mut f);previous=expected;
  }
 }
 #[test]fn tapped_instance_and_hd_prediction_keep_the_same_neighbouring_songs(){
  let(mut r,mut f)=ready();let clicked=block_index(1,4);let song=r.song(clicked);
  r.command(1,clicked as i32,0.,0.);r.command(29,song as i32,0.,0.);r.command(2,song as i32,1.,0.);assert_eq!(r.common.playing,Some(clicked));
  r.command(2,(song+1)as i32,1.,0.);let target=r.common.playing.unwrap();assert_eq!(target,block_index(1,5));
  let(mut preview,mut predicted)=ready();preview.command(10,1024,0.,(song+1)as f32);
  preview.frame(0.,400.,890.,&mut predicted);r.frame(0.,400.,890.,&mut f);
  let(pc,pr)=block(preview.common.playing.unwrap());let(c,z)=block(target);
  for dx in -3..=3{for dz in -20..=20{assert_eq!(r.song(block_index(c+dx,z+dz)),preview.song(block_index(pc+dx,pr+dz)));}}
 }

 #[test]fn free_browse_holds_position_until_playback_changes_or_locate(){
  let(mut r,mut f)=ready();r.command(10,1024,0.,0.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}
  let original=r.source(0,1.).p;
  r.command(26,0,0.,0.);r.command(27,0,80.,60.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}assert!(r.waves.is_empty());
  let pan=(r.pan_x.value,r.pan_z.value);let moved=r.source(0,1.).p;
  let fold=r.basis(V(0.,r.wave_height(0)-PEAK,0.)).mul(r.scale());
  assert!((moved.0-original.0-fold.0-80.).abs()<0.01&&(moved.1-original.1-fold.1-60.).abs()<0.01);
  r.command(28,1,0.,0.);for _ in 0..300{r.frame(1./120.,400.,890.,&mut f);}assert_eq!(r.wave_height(0),PEAK);
  assert_eq!((r.pan_x.value,r.pan_z.value),pan);assert_eq!(f[0],0.);
  r.command(2,1,1.,0.);for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}assert_ne!((r.pan_x.value,r.pan_z.value),pan);assert!(!r.manual_view);
  r.command(14,0,0.,0.);for _ in 0..240{r.frame(1./120.,400.,890.,&mut f);}
  let p=r.source(r.common.playing.unwrap(),1.).p;assert!((p.0-200.).abs()<0.01&&(p.1-445.).abs()<0.01);assert_eq!(f[0],0.);
 }

 #[test]fn browsing_reveals_the_actual_cover_while_playback_retracts(){
  let(mut r,mut f)=ready();r.command(10,1024,0.,0.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}
  r.command(26,0,0.,0.);r.command(27,0,140.,180.);
  for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}assert!(r.waves.is_empty());assert!(r.wave_height(0)<0.5);
  let cell=r.view_cell();assert!(r.wave_height(cell)>1.05,"browse cover must be clearly raised");
  let pose=r.source(cell,1.);let mut records=Vec::new();
  for n in 0..f[16]as usize{let mut matrix=[0.;16];matrix.copy_from_slice(&f[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);records.push(rhine_gpu::HitRecord{cell:f[HEADER+n*STRIDE+7]as usize,matrix,alpha:f[HEADER+n*STRIDE+9],owner:false});}
  for x in [-0.23,0.,0.23]{for y in [0.,0.23,0.46]{let point=pose.point(V(x,y,PRINT_Z));assert_eq!(rhine_gpu::hit(&records,point.0,point.1,(200.,445.),1.),cell as i32,"browse cover occluded at {x},{y}");}}
  let overlap=cover_overlap(&r,&f,cell);println!("Inspected cover: projected overlap {:.2}%",overlap*100.);
  assert!((0.35..=0.40).contains(&overlap),"inspection must use its own active height: {overlap}");
  r.command(28,1,0.,0.);for _ in 0..300{r.frame(1./120.,400.,890.,&mut f);}assert_eq!(r.wave_height(0),PEAK);assert_eq!(f[0],0.);
 }

 #[test]fn release_restores_playback_without_jumping_poses_or_moving_the_camera(){
  for release in [0,1]{
   let(mut r,mut f)=ready();r.command(10,1024,0.,0.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}
   r.command(26,0,0.,0.);r.command(27,0,140.,180.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}
   assert!(r.waves.is_empty());let pan=(r.pan_x.value,r.pan_z.value);let inspected=r.view_cell();let held=r.wave_height(inspected);
   let poses=f[RHINE_POSES..].to_vec();r.command(28,release,0.,0.);r.frame(0.,400.,890.,&mut f);
   assert_eq!(&f[RHINE_POSES..],&poses,"release must retarget rather than teleport");
   for frame in 0..240{r.frame(1./120.,400.,890.,&mut f);assert_eq!((r.pan_x.value,r.pan_z.value),pan);
    if frame%10==0{for n in 0..f[16]as usize{let cell=f[HEADER+n*STRIDE+7]as usize;let pose=r.source(cell,1.).p;let m=RHINE_POSES+n*16;
     assert!((f[m+12]-pose.0).abs()<0.002&&(f[m+13]-pose.1).abs()<0.002&&(f[m+14]-pose.2).abs()<0.002,"stale retained inspection pose for {cell}");
    }}
   }
   assert_eq!(r.wave_height(0),PEAK);assert!((r.wave_height(inspected)-held).abs()<0.001);assert_eq!(f[0],0.);
  }
 }

 #[test]fn public_drag_ingress_retracts_and_restores_the_playing_card_and_hill(){
  let id=create();let mut f=vec![0.;FLOATS];assert!(command(id,10,1024,0.,0.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./90.,400.,890.,&mut f));}
  let state=entry(id).unwrap();
  // Ordinary selection/long press does not enter dragging (26 is touch-slop gated in Java).
  assert!(command(id,1,0,0.,0.));step(id,0.,400.,890.,&mut f);
  {let runtime=state.scene.lock().unwrap();assert_eq!(runtime.portrait.wave_height(0),PEAK);}
  assert!(command(id,15,0,0.,0.));for _ in 0..100{step(id,1./90.,400.,890.,&mut f);}
  {let runtime=state.scene.lock().unwrap();assert_eq!(runtime.portrait.wave_height(0),PEAK);}
  assert!(command(id,16,0,0.,0.));for _ in 0..120{step(id,1./90.,400.,890.,&mut f);}
  // Starting a drag at the playing cell must not resurrect it via browse_wave.
  for dt in [0.08,0.04,0.12,0.06,1.6]{
   assert!(command(id,26,0,0.,0.));step(id,0.,400.,890.,&mut f);
   let before={let runtime=state.scene.lock().unwrap();let r=&runtime.portrait;(r.waves.height(0),r.waves.velocity(0))};
   for _ in 0..(dt*90.)as usize{step(id,1./90.,400.,890.,&mut f);}
   let now={let runtime=state.scene.lock().unwrap();let r=&runtime.portrait;
    if dt>1.{assert!(r.waves.is_empty());assert!(r.browse_wave.is_empty());assert!((r.wave_height(0)-ambient(0)).abs()<0.00001);}
    (r.waves.height(0),r.waves.velocity(0))};
   assert!(now.0.is_finite()&&before.0.is_finite());
   assert!(command(id,28,1,0.,0.));step(id,0.,400.,890.,&mut f);
   {let runtime=state.scene.lock().unwrap();let r=&runtime.portrait;assert_eq!((r.waves.height(0),r.waves.velocity(0)),now);}
   for _ in 0..9{step(id,1./90.,400.,890.,&mut f);}
  }
  for _ in 0..240{step(id,1./90.,400.,890.,&mut f);}
  {let runtime=state.scene.lock().unwrap();let r=&runtime.portrait;assert_eq!(r.wave_height(0),PEAK);assert!((r.wave_height(block_index(0,-1))-r.wave_height(block_index(0,1))).abs()<0.01);}
  assert_eq!(f[0],0.);release(id);
 }

 #[test]fn hd_prefetch_tracks_browse_crest_and_predicts_the_drag_direction(){
  let(mut r,mut f)=ready();r.command(10,1024,0.,0.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}
  r.command(26,0,0.,0.);r.command(27,0,140.,180.);
  for _ in 0..100{r.frame(1./120.,400.,890.,&mut f);}
  // Explicit native velocity gives a deterministic future crest, independent of touch rate.
  r.pan_z.velocity=-4.;r.pan_x.velocity=0.;r.rebuild();
  let start=HEADER+STRIDE*SLOTS;let current=r.view_cell();let(x,z)=block(current);
  assert_eq!(r.cache[start]as usize,r.song(current));
  let future=r.song(block_index(x,z+14));let behind=r.song(block_index(x,z-14));
  let list=&r.cache[start..start+r.cache[22]as usize];
  let front_rank=list.iter().position(|&v|v as usize==future).unwrap();
  let back_rank=list.iter().position(|&v|v as usize==behind).unwrap();
  assert!(front_rank<32&&front_rank<back_rank,"future cover must prepare before it opens");
  let mut unique=std::collections::HashSet::new();assert!(list.iter().all(|v|unique.insert(*v as usize)));
  r.frame(0.,400.,890.,&mut f);assert_eq!(f[RHINE+12],1.);
  r.command(28,1,0.,0.);r.command(2,1,1.,0.);r.frame(0.,400.,890.,&mut f);assert_eq!(f[RHINE+12],0.);
 }
 #[test]fn cover_emission_tracks_playback_browse_and_sleeps_with_the_wave(){
  let(mut r,mut f)=ready();assert!((0..f[16]as usize).all(|n|f[HEADER+n*STRIDE+5]==0.));
  r.command(10,1024,0.,0.);for _ in 0..180{r.frame(1./120.,400.,890.,&mut f);}
  assert!(r.cover_emission(0)>0.99);
  r.command(26,0,0.,0.);r.command(27,0,140.,180.);
  for _ in 0..120{r.frame(1./120.,400.,890.,&mut f);}
  let cell=r.view_cell();let slot=r.slots[&cell];assert!(f[HEADER+slot*STRIDE+5]>0.9,"browse crest must emit too");
  r.command(28,1,0.,0.);for _ in 0..300{r.frame(1./120.,400.,890.,&mut f);}
  assert_eq!(f[0],0.,"lighting must not keep scheduling frames");
  let before=f[HEADER+slot*STRIDE+5];r.frame(0.,400.,890.,&mut f);assert_eq!(f[HEADER+slot*STRIDE+5],before);
 }
 #[test]fn lifted_caption_uses_one_face_space_layout_through_open_close_and_reopen(){
  let(mut r,mut f)=ready();let reference=r.caption_reference_size();let mut visible_samples=0;
  for _ in 0..2{
   r.command(15,0,0.,0.);
   for _ in 0..150{r.frame(1./120.,400.,890.,&mut f);
    assert!((f[RHINE+15]-reference*0.92).abs()<0.001);assert!((f[RHINE+16]-reference*0.19).abs()<0.001);
    let slot=f[ACTION+23]as usize;let o=HEADER+slot*STRIDE;
    if f[o+6]>0.01{visible_samples+=1;assert!((f[o+14]*reference-r.pose.size).abs()<0.001);}
   }
   let slot=f[ACTION+23]as usize;assert!((f[HEADER+slot*STRIDE+14]-1.).abs()<0.001,"settled text must not grow to 1.6x");
   r.command(23,0,0.,0.);for _ in 0..150{r.frame(1./120.,400.,890.,&mut f);}
  }
  assert!(visible_samples>60);
 }
 #[test]fn nearby_light_is_published_in_unpadded_scene_coordinates(){
  let id=create();let mut f=vec![0.;FLOATS];assert!(command(id,30,1,0.,0.));assert!(command(id,10,1024,0.,-1.));
  for(w,h)in[(400.,890.),(1200.,800.),(800.,1200.)]{
   assert!(step(id,0.,w,h,&mut f));let scale=w.max(h);let expected=[w*0.5,h*0.5,-scale*1.5,scale*0.38];
   assert_eq!(&f[RHINE+8..RHINE+12],&expected);
   assert!(command(id,26,0,0.,0.));assert!(command(id,27,0,80.,60.));assert!(step(id,0.1,w,h,&mut f));
   assert_eq!(&f[RHINE+8..RHINE+12],&expected);assert!(command(id,28,0,0.,0.));
  }release(id);
 }

 #[test]fn hologram_turns_fifteen_percent_toward_screen_around_its_attachment(){
  let q=Q::axis(V(1.,0.,0.),PITCH.to_radians()).mul(Q::axis(V(0.,1.,0.),YAW.to_radians()));
  let m=Pose{p:V(200.,445.,0.),q,size:228.}.matrix();let [right,down]=hologram_basis(&m);
  let normal=V(down.1*right.2-down.2*right.1,down.2*right.0-down.0*right.2,down.0*right.1-down.1*right.0).unit();
  let before=(-V(m[8],m[9],m[10]).unit().2).acos();let after=(-normal.2).acos();
  assert!((after-before*0.85).abs()<0.00001);
  assert!((right.norm()-228.).abs()<0.0001&&(down.norm()-228.).abs()<0.0001);
  assert!(right.unit().dot(down.unit()).abs()<0.00001);
  for alpha in [0.,0.5,1.]{let p=hologram_plane(&m,alpha);let x=RIGHT+0.08;let y=TOP+0.12-(1.-alpha)*0.04;let z=PRINT_Z+0.01;
   let anchor=(m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13]);
   assert!((p[4]+p[0]*HOLO_WIDTH+p[2]*HOLO_HEIGHT-anchor.0).abs()<0.0001);
   assert!((p[5]+p[1]*HOLO_WIDTH+p[3]*HOLO_HEIGHT-anchor.1).abs()<0.0001);
  }
 }
 #[test]fn hologram_public_inspection_playback_context_and_idle(){
  for(w,h)in[(400.,890.),(1200.,800.)]{
   let id=create();let mut f=vec![0.;FLOATS];let start=RHINE+RHINE_HOLOGRAM;
   let settle=|f:&mut Vec<f32>|{for _ in 0..240{assert!(step(id,1./90.,w,h,f));}};
   let visible=|f:&[f32]|(0..HOLOGRAM_SLOTS).map(|i|start+i*HOLOGRAM_STRIDE).filter(|&a|f[a+6]>0.6).collect::<Vec<_>>();
   assert!(command(id,30,1,0.,0.));assert!(command(id,10,1024,0.,-1.));assert!(command(id,3,1,0.,0.));settle(&mut f);
   assert!(visible(&f).is_empty());assert_eq!(f[0],0.);
   assert!(command(id,26,0,0.,0.));assert!(command(id,27,0,80.,60.));settle(&mut f);
   let labels=visible(&f);assert_eq!(labels.len(),1,"unplayed inspection needs one title and play target");
   let a=labels[0];let cell=f[a]as i32;let song=f[a+1]as i32;assert_eq!(f[a+8],1.);
   let state=entry(id).unwrap();let runtime=state.scene.lock().unwrap();assert_eq!(cell as usize,runtime.portrait.view_cell());drop(runtime);
   let slot=(0..f[16]as usize).find(|&s|f[HEADER+s*STRIDE+7]==cell as f32).unwrap();
   assert_eq!(f[a+1],f[HEADER+slot*STRIDE]);let m=&f[RHINE_POSES+slot*16..RHINE_POSES+(slot+1)*16];
   let expected=hologram_plane(m,1.);
   for (actual,expected) in [f[a+7],f[a+9],f[a+10],f[a+11],f[a+2],f[a+3]].iter().zip(expected){assert!((*actual-expected).abs()<0.002);}
   assert!(f[a+9].abs()>0.1,"text must follow the tilted album baseline");
   let bounds=hologram_bounds(&f[a..a+HOLOGRAM_STRIDE]);assert!(bounds[0]>=0.&&bounds[2]<=w&&bounds[1]>=0.&&bounds[3]<=h);
   // Round-trip the icon centre through the very same 2D projection used by
   // Android's draw/input matrices, including the oblique/sheared baseline.
   let local=(HOLO_WIDTH-26.,HOLO_HEIGHT*0.5);
   let screen=(f[a+2]+f[a+7]*local.0+f[a+10]*local.1,f[a+3]+f[a+9]*local.0+f[a+11]*local.1);
   let det=f[a+7]*f[a+11]-f[a+10]*f[a+9];let dx=screen.0-f[a+2];let dy=screen.1-f[a+3];
   let hit=((dx*f[a+11]-dy*f[a+10])/det,(dy*f[a+7]-dx*f[a+9])/det);
   assert!((hit.0-local.0).abs()<0.001&&(hit.1-local.1).abs()<0.001);
   assert_eq!(f[0],0.,"a settled inspected hologram must not spin");
   // Same public order as the Java button -> host explicit playback route.
   assert!(command(id,28,0,0.,0.));assert!(command(id,1,cell,0.,0.));assert!(command(id,29,song,0.,0.));assert!(command(id,2,song,1.,0.));settle(&mut f);
   assert_eq!(f[ACTION+22],cell as f32);let a=visible(&f)[0];assert_eq!((f[a],f[a+1]),(cell as f32,song as f32));
   assert!(command(id,26,0,0.,0.));assert!(step(id,0.,w,h,&mut f));
   assert_eq!(f[a+8],0.,"retracting playback cannot retain an active play hit target");
   settle(&mut f);assert!(visible(&f).iter().all(|&a|f[a]!=cell as f32));
   assert!(command(id,28,0,0.,0.));settle(&mut f);
   assert!(command(id,15,cell,0.,0.));assert!(step(id,1./90.,w,h,&mut f));
   for i in 0..HOLOGRAM_SLOTS{assert_eq!(f[start+i*HOLOGRAM_STRIDE+8],0.);}
   settle(&mut f);assert!(visible(&f).is_empty());assert_eq!(f[ACTION+3],1.);
   assert!(command(id,16,0,0.,0.));settle(&mut f);assert_eq!(visible(&f).len(),1);assert_eq!(f[0],0.);
   let revision=f[23];assert!(step(id,0.,w,h,&mut f));assert_eq!(f[23],revision);assert_eq!(f[0],0.);
   release(id);
  }
 }
 #[test]fn hologram_fast_retargets_keep_unique_cells_and_finite_snapshots(){
  let id=create();let mut f=vec![0.;FLOATS];assert!(command(id,30,1,0.,0.));assert!(command(id,10,1024,0.,0.));assert!(command(id,3,1,0.,0.));
  for i in 0..120{
   if i%8==0{assert!(command(id,2,(i/8*7)%1024,1.,0.));}
   assert!(step(id,1./90.,400.,890.,&mut f));let mut seen=HashSet::new();
   for n in 0..HOLOGRAM_SLOTS{let a=RHINE+RHINE_HOLOGRAM+n*HOLOGRAM_STRIDE;
    assert!(f[a..a+HOLOGRAM_STRIDE].iter().all(|v|v.is_finite()));
    if f[a+6]>0.{assert!(seen.insert(f[a]as usize));assert!((0.0..=1.0).contains(&f[a+6]));}
   }
  }
  for _ in 0..360{assert!(step(id,1./90.,400.,890.,&mut f));}assert_eq!(f[0],0.);release(id);
 }

}
