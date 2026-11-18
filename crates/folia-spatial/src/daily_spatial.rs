//! Daily recommendation wall. Mailbox inputs, GL-worker-owned scene, Rust clocks/geometry and
//! picking. Output is a bounded visible window, never a limit on the recommendation catalogue.
use std::collections::{HashMap,HashSet};
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicI64, Ordering};
mod protocol;
pub use protocol::*;
mod motion;
use motion::Spring;
mod layout;
pub use layout::base;
use layout::{block,block_index,base_at,caption_region};
mod input;
use input::hit_geometry;
mod frame;
mod interaction;
mod runtime;
mod arrival;
mod rhine_math;
mod rhine_wave;
mod rhine;
pub mod rhine_mesh;
pub mod rhine_gpu;
#[cfg(test)] mod tests;
use interaction::LiftPlacement;
const ZOOM_MIN:f32=0.45;
const ZOOM_MAX:f32=5.0;
const CELL: f32 = 58.0;
const GAP: f32 = 12.0;
#[derive(Clone, Copy, Debug, Default)]
pub struct Rect {pub x:f32,pub y:f32,pub w:f32,pub h:f32}
type Candidate=(usize,Rect,f32,f32,f32,f32,f32,usize);
fn candidate_order(a:&Candidate,b:&Candidate)->std::cmp::Ordering{a.2.total_cmp(&b.2).then(a.0.cmp(&b.0))}
fn nearest_indices(indices:&mut Vec<usize>,candidates:&[Candidate],limit:usize){
 let compare=|a:&usize,b:&usize|candidate_order(&candidates[*a],&candidates[*b]);
 if indices.len()>limit{indices.select_nth_unstable_by(limit,compare);indices.truncate(limit);}
 indices.sort_unstable_by(compare);
}
#[derive(Default)]
pub struct State {
 count:usize,selected:usize,playing:Option<usize>,playing_song:Option<usize>,running:bool,follow:bool,
 x:Spring,y:Spring,zoom:Spring,mode:Spring,labels:Spring,
 weights:HashMap<usize,f32>,width:f32,height:f32,header:f32,scroll:f32,
 visible:Vec<(usize,Rect)>,visible_scales:Vec<f32>,visible_songs:Vec<usize>,more:bool,quiet:f32,
 bindings:HashMap<usize,usize>,next_bindings:HashMap<usize,usize>,prefetch_seen:HashSet<usize>,
 nearby:Vec<(usize,Rect)>, candidates:Vec<Candidate>,visible_order:Vec<usize>,prefetch_order:Vec<usize>,prefetch_best:HashMap<usize,usize>,
 edge_x:Vec<f32>,edge_y:Vec<f32>,warped_x:Vec<f32>,warped_y:Vec<f32>,edge_indices:Vec<[usize;4]>,
 action_cell:Option<usize>,lift_slot:Option<usize>,action_phase:i32,lift_origin:Rect,lift_placement:LiftPlacement,lift_dismissed:bool,
 menu:Spring,lift:Spring,confirmation:Spring,lift_shadow:Spring,
 capture_hold:bool,hidden_cell:Option<usize>,fill_cell:Option<usize>,fill:Spring,
 region:Option<(i32,i32,i32,i32,usize)>, cache:Vec<f32>, signature:[u32;9], revision:u32,world_revision:u32,world_signature:[u32;8],
 warps:Vec<(f32,f32,f32,f32)>,lead_x:f32,lead_y:f32,pinching:bool,pinch_raw:f32,
}
impl State {
 fn new()->Self{Self{zoom:Spring::new(1.0),follow:true,labels:Spring::new(1.0),header:200.0,
  visible:Vec::with_capacity(VISIBLE),visible_scales:Vec::with_capacity(VISIBLE),visible_songs:Vec::with_capacity(VISIBLE),
  visible_order:Vec::with_capacity(1024),prefetch_order:Vec::with_capacity(1024),prefetch_best:HashMap::with_capacity(1024),
  edge_x:Vec::with_capacity(512),edge_y:Vec::with_capacity(512),warped_x:Vec::with_capacity(512),warped_y:Vec::with_capacity(512),edge_indices:Vec::with_capacity(1024),nearby:Vec::with_capacity(1024),candidates:Vec::with_capacity(1024),
  bindings:HashMap::with_capacity(1024),next_bindings:HashMap::with_capacity(1024),prefetch_seen:HashSet::with_capacity(VISIBLE+PREFETCH),
  weights:HashMap::with_capacity(16),warps:Vec::with_capacity(16),cache:vec![0.;FLOATS],signature:[u32::MAX;9],..Self::default()}}
 // A spatial cell and a track are separate identities. Repeated tracks may occupy different
 // rectangles, but playback expands only the chosen instance. Keep assignments for overlap
 // when paging appends tracks; only newly encountered cells use the larger catalogue.
 fn song(&self,cell:usize)->usize{self.bindings.get(&cell).copied().filter(|&s|s<self.count).unwrap_or(cell%self.count.max(1))}
 fn playback_cell(&self,song:usize)->usize{
  if self.song(self.selected)==song{return self.selected}
  self.nearby.iter().filter(|&&(cell,_)|self.song(cell)==song).min_by(|a,b|{
   let distance=|r:Rect|(r.x+r.w/2.+self.x.value).powi(2)+(r.y+r.h/2.+self.y.value).powi(2);
   distance(a.1).total_cmp(&distance(b.1))
  }).map(|v|v.0).unwrap_or_else(||if self.action_cell==Some(song){song+self.count.max(1)}else{song})
 }
 #[cfg(test)]
 fn hit(&self,x:f32,y:f32)->i32{
  hit_geometry(self.mode.value,self.x.value,self.y.value,self.zoom.value,self.width,self.height,self.camera().1,&self.visible,x,y)
 }
}
#[derive(Clone,Copy)]
struct Command{op:i32,index:i32,a:f32,b:f32}
struct HitScene{list_y:f32,songs:Vec<f32>,mode:f32,x:f32,y:f32,zoom:f32,width:f32,height:f32,center_y:f32,visible:Vec<(usize,Rect)>,rhine:bool,revision:u32,background:f32,records:Vec<rhine_gpu::HitRecord>}
impl HitScene{
 fn new()->Self{Self{list_y:0.,songs:Vec::new(),mode:0.,x:0.,y:0.,zoom:1.,width:0.,height:0.,center_y:0.,visible:Vec::with_capacity(VISIBLE),rhine:false,revision:u32::MAX,background:1.,records:Vec::with_capacity(SLOTS)}}
 fn update(&mut self,s:&State,frame:&[f32]){
  self.rhine=false;self.mode=s.mode.value;self.x=s.x.value;self.y=s.y.value;self.zoom=s.zoom.value;self.width=s.width;self.height=s.height;self.center_y=s.camera().1;
  self.mode=frame[1];self.list_y=frame[20]+8.-frame[21];self.songs.clear();
  let morphing=self.mode>0. && self.mode<1.;
  self.visible.clear();if morphing || frame[ACTION+129]>0.5 {
   for n in 0..frame[16]as usize{let o=HEADER+n*STRIDE;if frame[o+9]>0.001{if morphing {self.songs.push(frame[o]);}self.visible.push((frame[o+7]as usize,Rect{x:frame[o+1],y:frame[o+2],w:frame[o+3],h:frame[o+4]}));}}
  }else{self.visible.extend_from_slice(&s.visible);}
 }
 fn update_rhine(&mut self,frame:&[f32]){
  // The per-runtime revision covers poses, slot identities, alpha, viewport and
  // mode. Cover decode/upload wakeups do not change it: retain the same hit
  // snapshot instead of recopying up to 2048 matrices under the UI hit lock.
  let revision=frame[23].to_bits();if self.rhine&&self.revision==revision{return}
  self.revision=revision;self.rhine=true;self.mode=frame[1];self.width=frame[9];self.height=frame[10];self.center_y=frame[8];self.background=frame[ACTION+127];self.records.clear();for n in 0..frame[16]as usize{let o=HEADER+n*STRIDE;let mut matrix=[0.;16];matrix.copy_from_slice(&frame[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);self.records.push(rhine_gpu::HitRecord{cell:frame[o+7]as usize,matrix,alpha:frame[o+9],owner:frame[ACTION+27]>0.5&&frame[ACTION+23]==n as f32});}
 }
 fn hit(&self,x:f32,y:f32)->i32 {
  if !x.is_finite() || !y.is_finite() || self.mode <= 0. { return -1 }
  if self.rhine { return rhine_gpu::hit(&self.records,x,y,(self.width/2.,self.center_y),self.background) }
  if self.mode < 1. { return self.hit_morph(x,y) }
  hit_geometry(self.mode,self.x,self.y,self.zoom,self.width,self.height,self.center_y,&self.visible,x,y)
 }

 // Invert the list-to-wall vertex projection at the touch point. A cell's list position
 // belongs to its song, while its curved position belongs to this particular instance.
 fn hit_morph(&self, x:f32, y:f32)->i32 {
  let mode = self.mode;
  let rx = (self.width * 1.3).max(900.);
  let ry = (self.height * 1.7).max(1100.);
  let mix = |a:f32,b:f32| a + (b-a)*mode;
  let mut nearest = f32::INFINITY;
  let mut hit = -1;
  for (&(cell,r), &song) in self.visible.iter().zip(&self.songs) {
   let project = |u:f32,v:f32| {
    let ax = (r.x + u*r.w + self.x)*self.zoom/rx;
    let ay = (r.y + v*r.h + self.y)*self.zoom/ry;
    let depth = rx*(1.-ax.cos()) + ry*(1.-ay.cos());
    let p = 1500./(1500.+depth);
    (mix(42.+54.*u, self.width/2.+rx*ax.sin()*p),
     mix(self.list_y+72.*song+54.*v, self.center_y+ry*ay.sin()*p), ax, ay, depth)
   };
   let (mut u, mut v) = (0.5, 0.5);
   let (cx, cy, ..) = project(u,v);
   let radius = mix(54., (r.w+r.h)*self.zoom);
   if (x-cx).abs()>radius || (y-cy).abs()>radius { continue }
   for _ in 0..6 {
    let (px,py,..) = project(u,v);
    let (dx,dy) = (x-px,y-py);
    if dx.abs()+dy.abs() < 0.05 { break }
    let (a,b,..) = project(u+0.01,v);
    let (c,d,..) = project(u,v+0.01);
    let (a,b,c,d) = (a-px,b-py,c-px,d-py);
    let det=a*d-b*c;
    if det.abs()<1e-7 { break }
    u += 0.01*(dx*d-dy*c)/det;
    v += 0.01*(dy*a-dx*b)/det;
   }
   if !(0. ..=1.).contains(&u) || !(0. ..=1.).contains(&v) { continue }
   let (px,py,ax,ay,depth) = project(u,v);
   if (px-x).abs()+(py-y).abs()>0.5 || (mode>0.01 && ax.abs().max(ay.abs())>1.22) { continue }
   let radius=mix(12.,6.);
   let qx=(u-0.5).abs()*mix(54.,r.w)-0.5*mix(54.,r.w)+radius;
   let qy=(v-0.5).abs()*mix(54.,r.h)-0.5*mix(54.,r.h)+radius;
   if qx.max(0.).hypot(qy.max(0.))+qx.max(qy).min(0.)<=radius && depth<nearest {
    hit=cell as i32;
    nearest=depth;
   }
  }
  hit
 }
}
struct Entry{scene:Mutex<runtime::Runtime>,pending:Mutex<Vec<Command>>,hit:Mutex<HitScene>}
static STATES:OnceLock<Mutex<HashMap<i64,Arc<Entry>>>>=OnceLock::new();
static NEXT:AtomicI64=AtomicI64::new(1);
fn states()->&'static Mutex<HashMap<i64,Arc<Entry>>>{STATES.get_or_init(||Mutex::new(HashMap::new()))}
fn entry(id:i64)->Option<Arc<Entry>>{states().lock().ok()?.get(&id).cloned()}
pub fn create()->i64{
 let state=Arc::new(Entry{scene:Mutex::new(runtime::Runtime::new()),pending:Mutex::new(Vec::with_capacity(64)),hit:Mutex::new(HitScene::new())});
 let Ok(mut map)=states().lock()else{return 0};let id=NEXT.fetch_add(1,Ordering::Relaxed);map.insert(id,state);id
}
pub fn release(id:i64){if let Ok(mut map)=states().lock(){map.remove(&id);}}
/// Main-thread inputs never acquire the geometry lock. Commands retain their original order;
/// only adjacent overwrite-only state changes are collapsed (never gesture deltas).
pub fn command(id:i64,op:i32,index:i32,a:f32,b:f32)->bool{
 if !a.is_finite()||!b.is_finite()||!matches!(op,0..=8|10..=33){return false}
 if matches!(op,0|10)&&!(0..=1_000_000).contains(&index){return false}
 if op==1&&index< -1{return false}
 if op==30&&!(0..=2).contains(&index){return false}
 let Some(entry)=entry(id)else{return false};let Ok(mut pending)=entry.pending.lock()else{return false};
 let next=Command{op,index,a,b};
 if matches!(op,0|2|3|7){if let Some(last)=pending.last_mut(){if last.op==op{*last=next;return true}}}
 pending.push(next);true
}
pub fn step(id:i64,dt:f32,w:f32,h:f32,out:&mut[f32])->bool{
 if !dt.is_finite()||!w.is_finite()||!h.is_finite()||w<=0.||h<=0.{return false}
 let Some(entry)=entry(id)else{return false};let Ok(mut scene)=entry.scene.lock()else{return false};scene.viewport(w,h);
 {
  let Ok(mut pending)=entry.pending.lock()else{return false};
  std::mem::swap(&mut scene.commands,&mut *pending);
 }
 for n in 0..scene.commands.len(){let c=scene.commands[n];scene.command(c.op,c.index,c.a,c.b);}
 scene.commands.clear();
 if !scene.frame(dt,w,h,out){return false}
 // Publish only the small hit-test snapshot. UI picking cannot wait for sort/projection.
 if let Ok(mut hit)=entry.hit.lock(){if scene.is_portrait{hit.update_rhine(out)}else{hit.update(&scene.landscape,out)}}true
}
pub fn hit(id:i64,x:f32,y:f32)->i32{
 let Some(entry)=entry(id)else{return -1};let Ok(hit)=entry.hit.lock()else{return -1};hit.hit(x,y)
}


#[cfg(test)]mod style_ingress_tests{
 use super::*;
 #[test]fn queued_style_and_explicit_play_commands_reach_runtime(){
  let id=create();let mut f=vec![0.;FLOATS];assert!(command(id,10,1024,0.,0.));assert!(command(id,3,1,0.,0.));
  assert!(command(id,30,2,0.,0.));
  // A visible/pending entry retains the outgoing route until its Rust fade completes.
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}assert_eq!(f[RHINE],0.);
  assert!(command(id,30,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}assert!(command(id,29,0,0.,0.));assert!(step(id,0.,400.,890.,&mut f));assert_eq!(f[RHINE],1.);
  assert!(!command(id,30,3,0.,0.));release(id);
 }
}
