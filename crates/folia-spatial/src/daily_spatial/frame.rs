//! Rust frame evaluation and bounded visible/preload snapshots.
use super::*;
impl State {
 pub(super) fn frame(&mut self,dt:f32,w:f32,h:f32,out:&mut[f32])->bool{
  if out.len()<FLOATS||!w.is_finite()||!h.is_finite()||w<=0.0||h<=0.0{return false}
  self.width=w;self.height=h;out[..FLOATS].fill(0.0);let dt=if self.capture_hold{0.}else{dt.clamp(0.0,0.25)};let mut layout=false;let mut shape_changed=false;
  if let Some(index)=self.playing{self.weights.entry(index).or_insert(0.0);}
  for(&index,value)in &mut self.weights{let goal=if Some(index)==self.playing{1.0}else{0.0};shape_changed|=*value!=goal;*value+=(goal-*value)*(dt*11.0).min(1.0);if(goal-*value).abs()>0.0005{layout=true}else{*value=goal;}}
  out[25]=if shape_changed{1.0}else{0.0};
  self.weights.retain(|_,v|*v>0.0);
  if shape_changed{self.rebuild_warps();}
  let world_shape_changed=shape_changed;let old_lift=self.lift.value;let old_shadow=self.lift_shadow.value;let old_fill=self.fill.value;
  let action_motion=self.menu.step_with(dt,520.,32.)|self.lift.step_lift(dt)|self.confirmation.step_with(dt,420.,36.)|self.fill.step(dt)|self.lift_shadow.step_with(dt,420.,36.);
  if self.action_phase==1&&self.lift.value>0.55{self.menu.target=1.;}
  if self.action_phase==2&&self.menu.value<0.08{self.confirmation.target=1.;}
  shape_changed|=old_lift!=self.lift.value||old_shadow!=self.lift_shadow.value;
  if self.action_phase==0&&self.lift.value==0.&&self.action_cell.is_some(){self.action_cell=None;self.signature=[u32::MAX;9];}
  self.update_lift_layout();
  let selected_center=if self.count>0{Some(self.center(self.selected))}else{None};
  if self.follow{if let Some((x,y))=selected_center{self.x.target=-x;self.y.target=-y;}}
  let zoom_moving=if self.pinching{let delta=self.zoom.target-self.zoom.value;self.zoom.value+=delta*(1.-(-90.*dt).exp());delta.abs()>0.0005}else{self.zoom.step(dt)};
  let camera_motion=self.x.step(dt)|self.y.step(dt);
  let mut moving=camera_motion|zoom_moving|self.mode.step(dt)|layout|action_motion;
  if self.fill_cell.is_some(){shape_changed|=self.fill.value<1.;if self.fill.value==1.{self.fill_cell=None;shape_changed=true;}}
  if self.action_phase==2&&self.menu.value<0.08&&self.confirmation.value<1.{moving=true;}
  moving|=self.action_phase==1&&self.menu.value!=self.menu.target;
  out[25]=if shape_changed{1.0}else{0.0};
  self.quiet=(self.quiet-dt).max(0.0);
  let camera_moving=self.x.velocity.abs()+self.y.velocity.abs()>0.5||(self.zoom.value-self.zoom.target).abs()>0.002;
  let typography_reflow=layout||self.lift.velocity.abs()>0.005||(self.mode.value-self.mode.target).abs()>0.005;
  let typography_moving=typography_reflow||camera_moving||self.quiet>0.0;
  // Camera gestures and their settling motion keep captions visible. Only card/layout
  // transitions retain the existing fade; the motion flag still drives frame consumers.
  self.labels.target=if self.action_cell.is_some()&&!self.lift_dismissed{1.0}else if typography_reflow{0.0}else{1.0};
  out[24]=if typography_moving{1.0}else{0.0};moving|=self.quiet>0.0;moving|=self.labels.step(dt);
  let (cx,cy,rx,ry)=self.camera();let (px,py)=if let Some((x,y))=selected_center{self.project(x,y)}else{(cx,cy)};
  out[0]=if moving&&!self.capture_hold{1.0}else{0.0};out[1]=self.mode.value.clamp(0.,1.);out[2]=self.x.value;out[3]=self.y.value;out[4]=self.zoom.value;out[5]=rx;out[6]=ry;out[7]=cx;out[8]=cy;out[9]=w;out[10]=h;out[11]=self.song(self.selected) as f32;out[12]=px;out[13]=py;out[14]=0.;out[15]=self.labels.value.clamp(0.,1.);out[18]=1.0-out[1];out[19]=-20.0*out[1];out[20]=self.header;out[21]=self.scroll;
  let [top,bottom,strength]=crate::render_policy::screen_edges(w,h);out[26]=top;out[27]=bottom;
  out[28]=(ry*(1.-(h*0.5/ry).cos())).max(24.);out[29]=14.;out[30]=strength;
  // Match the View visibility threshold exactly. Hidden captions do not need projected
  // corners, depth factors or typography geometry during expansion. Include the flag in
  // the cache key so they are rebuilt before the first returning fade-in frame.
  let captions_visible=out[1]>=0.95&&out[15]>=0.1;
  let world_signature=[self.x.value.to_bits(),self.y.value.to_bits(),self.zoom.value.to_bits(),self.mode.value.to_bits(),w.to_bits(),h.to_bits(),self.count as u32,self.scroll.to_bits()];
  if world_shape_changed||old_fill!=self.fill.value||world_signature!=self.world_signature||self.world_revision==0{
   self.world_signature=world_signature;self.world_revision=self.world_revision%1_000_000+1;
  }
  let signature=[self.x.value.to_bits(),self.y.value.to_bits(),self.zoom.value.to_bits(),self.mode.value.to_bits(),w.to_bits(),h.to_bits(),self.count as u32,self.scroll.to_bits(),captions_visible as u32];
  if !world_shape_changed&&old_fill==self.fill.value&&signature==self.signature&&self.revision>0{
   // Lift-only frames keep world culling/order/warp caches; update only foreground captions.
   if shape_changed{self.revision=self.revision%1_000_000+1;}
   out[16]=self.cache[16];out[17]=self.cache[17];out[22]=self.cache[22];out[23]=self.revision as f32;out[31]=self.cache[31];
   out[HEADER..FLOATS].copy_from_slice(&self.cache[HEADER..FLOATS]);self.present_lift(out);self.write_actions(out);return true;
  }
  self.signature=signature;self.revision=self.revision%1_000_000+1;out[23]=self.revision as f32;
  self.visible.clear();self.visible_scales.clear();self.visible_songs.clear();self.candidates.clear();self.more=false;
  let pad=240.0;let half_x=(w*0.75+self.lead_x.abs())/self.zoom.value+pad;let half_y=(h*0.75+self.lead_y.abs())/self.zoom.value+pad;
  let min_x=((-self.x.value-half_x)/CELL/6.0).floor() as i32-1;
  let max_x=((-self.x.value+half_x)/CELL/6.0).ceil() as i32+1;
  let min_y=((-self.y.value-half_y)/CELL/6.0).floor() as i32-1;
  let max_y=((-self.y.value+half_y)/CELL/6.0).ceil() as i32+1;
  let region=(min_x,max_x,min_y,max_y,self.count);
  let region_changed=self.region!=Some(region);
  if region_changed{
   self.nearby.clear();self.next_bindings.clear();
   let cells=(max_x-min_x+1)as usize*(max_y-min_y+1)as usize*8;
   self.nearby.reserve(cells);self.candidates.reserve(cells);
   self.next_bindings.reserve(cells+self.weights.len()+1);
   for by in min_y..=max_y{for bx in min_x..=max_x{let start=block_index(bx,by)*8;
    if self.count>0{for index in start..start+8{
     self.nearby.push((index,base_at(index,bx,by)));self.next_bindings.insert(index,self.song(index));
    }}
   }}
   self.next_bindings.insert(self.selected,self.song(self.selected));
   for &cell in self.weights.keys(){self.next_bindings.insert(cell,self.song(cell));}
   std::mem::swap(&mut self.bindings,&mut self.next_bindings);self.region=Some(region);self.index_edges();
  }
  if region_changed||world_shape_changed{self.update_edges();}
  // Reject the back of the curved sheet before projection. It must not wrap into the viewport.
  for (n,&(index,_))in self.nearby.iter().enumerate(){
   let r=self.cached_rect(n);let ax=(r.x+self.x.value)*self.zoom.value/rx;let ay=(r.y+self.y.value)*self.zoom.value/ry;
   if !self.is_lifted(index)&&(ax>1.22||ay>1.22||ax+r.w*self.zoom.value/rx< -1.22||ay+r.h*self.zoom.value/ry< -1.22){continue}
   let(sx,sy,scale)=self.projected(r.x+r.w/2.,r.y+r.h/2.);let radius=(r.w+r.h)*self.zoom.value*0.55;
   if !self.is_lifted(index)&&(sx+radius< -180.-self.lead_x.max(0.)||sx-radius>w+180.-self.lead_x.min(0.)||sy+radius< -180.-self.lead_y.max(0.)||sy-radius>h+80.-self.lead_y.min(0.)){continue}
   let distance=if self.is_lifted(index){-1.}else{(sx+self.lead_x-cx).powi(2)+(sy+self.lead_y-cy).powi(2)};
   self.candidates.push((index,r,distance,sx,sy,radius,scale,self.song(index)));
  }
  // Preserve the full-sort result exactly, without sorting unused offscreen candidates.
  self.visible_order.clear();self.prefetch_order.clear();self.prefetch_best.clear();self.prefetch_seen.clear();
  for(n,&(cell,_,_,sx,sy,radius,_,song))in self.candidates.iter().enumerate(){
   if self.is_lifted(cell)||(sx+radius>=0.&&sx-radius<=w&&sy+radius>=0.&&sy-radius<=h){self.visible_order.push(n);}
   if song.saturating_add(384)>=self.count{self.more=true;}
  }
  nearest_indices(&mut self.visible_order,&self.candidates,VISIBLE);
  for &n in &self.visible_order{
   let(index,r,_,_,_,_,scale,song)=self.candidates[n];
   self.visible.push((index,r));self.visible_scales.push(scale);self.visible_songs.push(song);self.prefetch_seen.insert(song);
  }
  for(n,candidate)in self.candidates.iter().enumerate(){
   let song=candidate.7;if self.prefetch_seen.contains(&song){continue}
   let best=self.prefetch_best.entry(song).or_insert(n);
   if candidate_order(candidate,&self.candidates[*best]).is_lt(){*best=n;}
  }
  self.prefetch_order.extend(self.prefetch_best.values().copied());
  nearest_indices(&mut self.prefetch_order,&self.candidates,PREFETCH);
  for(ahead,&n)in self.prefetch_order.iter().enumerate(){out[HEADER+STRIDE*SLOTS+ahead]=self.candidates[n].7 as f32;}
  out[22]=self.prefetch_order.len()as f32;
  if self.mode.value<0.99{let first=(self.scroll/72.0).floor().max(0.0)as usize;for index in first..(first+((h/72.0)as usize+3)).min(self.count){if self.visible.len()>=VISIBLE{break}if !self.visible.iter().any(|v|v.0==index){let r=self.live(index);self.visible.push((index,r));self.visible_songs.push(self.song(index));self.visible_scales.push(self.projected(r.x+r.w/2.,r.y+r.h/2.).2);}}}
  self.lift_slot=self.action_cell.and_then(|cell|self.visible.iter().position(|v|v.0==cell));
  out[16]=self.visible.len() as f32;
  for(slot,&(index,r))in self.visible.iter().enumerate(){out[31]=out[31].max(r.w.max(r.h)*self.zoom.value);let o=HEADER+slot*STRIDE;out[o]=self.visible_songs[slot] as f32;out[o+7]=index as f32;out[o+8]=self.visible_scales[slot];out[o+9]=if self.hidden_cell==Some(index){0.}else if self.fill_cell==Some(index){self.fill.value.clamp(0.,1.)}else{1.};out[o+1]=r.x;out[o+2]=r.y;out[o+3]=r.w;out[o+4]=r.h;out[o+5]=*self.weights.get(&index).unwrap_or(&0.0);
   let(tx,ty,tw,th,over_art,font_scale)=caption_region(r);out[o+15]=if over_art{1.}else{0.};
   let m=MAP_CAPTIONS+slot*16;out[m]=tw;out[m+1]=th;out[m+2]=font_scale;
   if !captions_visible{continue}
   if tw>=48.&&th>=30.{let(a,b)=self.project(tx,ty);let(c,d)=self.project(tx+tw,ty+th);out[o+10]=a;out[o+11]=b;out[o+12]=(c-a).max(0.);out[o+13]=(d-b).max(0.);
    // One uniform scale for glyphs. No separate X/Y font transforms.
    out[o+6]=1.-crate::render_policy::edge_factor(b+((d-b)*0.5),h,out[26],out[27]);
    out[o+14]=(((c-a)/tw).min((d-b)/th)*font_scale).clamp(0.5,3.5);}


  }
  out[17]=if self.more||(self.count>0&&self.visible.is_empty()){1.0}else{0.0};
  self.cache.copy_from_slice(&out[..FLOATS]);self.present_lift(out);self.write_actions(out);true
 }
}
