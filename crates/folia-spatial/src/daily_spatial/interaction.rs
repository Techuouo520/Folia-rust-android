//! Context-card presentation. Pure Rust geometry/state; no Android, GL or player dependency.
use super::*;

#[derive(Clone,Copy,Default)]
pub(super) struct LiftPlacement {pub card:Rect,pub menu:Rect}
fn placement(origin:Rect,world:Rect,width:f32,height:f32)->LiftPlacement{
 let top=76.;let bottom=(height-210.).max(top+160.);let margin=20.;let gap=14.;let menu_h=120.;
 let horizontal=height<600.&&width>600.;let menu_w=260_f32.min((width-2.*margin).max(160.));
 let mut scale=((origin.w*1.12).max(224.).min(320.).min(width*0.72)/world.w.max(1.)).max(0.01);
 let available_h=if horizontal{bottom-top}else{(bottom-top-menu_h-gap).max(48.)};
 scale=scale.min(available_h/world.h.max(1.));
 if horizontal{scale=scale.min((width-2.*margin-menu_w-gap).max(48.)/world.w.max(1.));}
 let(w,h)=(world.w*scale,world.h*scale);let tray_w=if horizontal{menu_w}else{w.max(240.).min(width-2.*margin)};
 let group_w=if horizontal{w+gap+tray_w}else{w.max(tray_w)};let group_h=if horizontal{h.max(menu_h)}else{h+gap+menu_h};
 let center=(origin.x+origin.w*0.5)*0.28+width*0.5*0.72;
 let center=center.clamp(margin+group_w*0.5,(width-margin-group_w*0.5).max(margin+group_w*0.5));
 let in_place=origin.y+(origin.h-h)*0.5;let centered=(top+bottom-group_h)*0.5;
 let group_y=(in_place*0.28+centered*0.72).clamp(top,(bottom-group_h).max(top));
 if horizontal{
  let x=center-group_w*0.5;
  LiftPlacement{card:Rect{x,y:group_y+(group_h-h)*0.5,w,h},menu:Rect{x:x+w+gap,y:group_y+(group_h-menu_h)*0.5,w:tray_w,h:menu_h}}
 }else{
  LiftPlacement{card:Rect{x:center-w*0.5,y:group_y,w,h},menu:Rect{x:center-tray_w*0.5,y:group_y+h+gap,w:tray_w,h:menu_h}}
 }
}
impl State{
 pub(super) fn action_base(&self,cell:usize)->Rect{self.warp_rect(base(cell))}
 pub(super) fn is_lifted(&self,cell:usize)->bool{self.action_cell==Some(cell)&&!self.lift_dismissed}
 pub(super) fn projected_bounds(&self,r:Rect)->Rect{
  let(mut l,mut t,mut right,mut bottom)=(f32::INFINITY,f32::INFINITY,f32::NEG_INFINITY,f32::NEG_INFINITY);
  for (u,v)in [(0.,0.),(0.5,0.),(1.,0.),(1.,0.5),(1.,1.),(0.5,1.),(0.,1.),(0.,0.5)]{
   let(x,y)=self.project(r.x+r.w*u,r.y+r.h*v);l=l.min(x);t=t.min(y);right=right.max(x);bottom=bottom.max(y);
  }Rect{x:l,y:t,w:(right-l).max(1.),h:(bottom-t).max(1.)}
 }
 pub(super) fn update_lift_layout(&mut self){
  if let Some(cell)=self.action_cell{self.lift_placement=placement(self.lift_origin,self.action_base(cell),self.width,self.height);}
 }
 #[cfg(test)]
 pub(super) fn lift_point(&self,x:f32,y:f32)->(f32,f32){
  let(cx,cy)=self.project(x,y);let Some(cell)=self.action_cell else{return(cx,cy)};
  self.lift_point_in(self.action_base(cell),x,y)
 }
 fn lift_point_in(&self,world:Rect,x:f32,y:f32)->(f32,f32){
  let(cx,cy)=self.project(x,y);let target=self.lift_placement.card;let p=self.lift.value.max(0.);
  (cx+(target.x+(x-world.x)/world.w*target.w-cx)*p,cy+(target.y+(y-world.y)/world.h*target.h-cy)*p)
 }
 pub(super) fn close_actions(&mut self){
  self.capture_hold=false;self.action_phase=0;self.menu.target=0.;self.confirmation.target=0.;self.lift.target=0.;self.lift_shadow.target=0.;
 }
 pub(super) fn finish_removal(&mut self){
  self.signature=[u32::MAX;9];self.close_actions();
  if let Some(cell)=self.hidden_cell.take(){self.lift_dismissed=true;self.fill_cell=Some(cell);self.fill.jump(0.);self.fill.target=1.;}
 }
 pub(super) fn context_command(&mut self,op:i32,index:i32)->bool{
  match op{
   15=>{
    if index<0||self.count==0||self.hidden_cell.is_some()||!self.visible.iter().any(|v|v.0==index as usize){return false}
    let cell=index as usize;let world=self.action_base(cell);self.lift_origin=self.projected_bounds(world);
    self.action_cell=Some(cell);self.selected=cell;self.follow=false;self.x.jump(self.x.value);self.y.jump(self.y.value);self.zoom.jump(self.zoom.value);
    self.lift_dismissed=false;self.action_phase=1;self.menu.target=0.;self.lift.target=1.;self.lift_shadow.target=1.;self.confirmation.target=0.;self.signature=[u32::MAX;9];
    self.update_lift_layout();
   }
   16=>{if self.action_phase!=3{self.close_actions();}}
   17=>{if self.action_phase!=1{return false}self.action_phase=2;self.menu.target=0.;}
   18=>{if self.action_phase!=2{return false}self.capture_hold=false;self.action_phase=3;self.hidden_cell=self.action_cell;self.signature=[u32::MAX;9];self.confirmation.target=0.;self.lift_shadow.target=0.;}
   19=>self.finish_removal(),
   21=>{if self.action_phase!=2||self.action_cell!=Some(index as usize){return false}self.capture_hold=true;}
   22=>self.capture_hold=false,
   _=>return false,
  }true
 }
 /// Keep world text geometry cached; only the lifted card needs reprojecting each frame.
 pub(super) fn present_lift(&self,out:&mut[f32]){
  let Some(slot)=self.lift_slot else{return};let(cell,r)=self.visible[slot];if !self.is_lifted(cell){return}
  let o=HEADER+slot*STRIDE;
  let(tx,ty,tw,th,_,font_scale)=caption_region(r);let(a,b)=self.lift_point_in(r,tx,ty);let(c,d)=self.lift_point_in(r,tx+tw,ty+th);
  out[o+10]=a;out[o+11]=b;out[o+12]=(c-a).max(0.);out[o+13]=(d-b).max(0.);out[o+6]=1.;
  out[o+14]=(((c-a)/tw).min((d-b)/th)*font_scale).clamp(0.5,3.5);
 }
 pub(super) fn write_actions(&self,out:&mut[f32]){
  let u=&mut out[ACTION..ACTION+192];u.fill(0.);u[3]=self.action_phase as f32;u[23]=-1.;u[4]=-1.;u[5]=-1.;u[22]=self.playing.map(|v|v as f32).unwrap_or(-1.);
  u[PLAYING_OPEN_PROGRESS]=self.playing.and_then(|cell|self.weights.get(&cell)).copied().unwrap_or(0.);
  u[0]=self.menu.value.clamp(0.,1.);u[1]=self.lift.value.clamp(0.,1.);u[2]=self.confirmation.value.clamp(0.,1.);
  u[20]=28.;u[24]=self.lift.value.max(0.);u[25]=0.94+0.06*u[2];u[26]=if self.capture_hold{1.}else{0.};u[124]=self.world_revision as f32;u[125]=VERSION as f32;
  u[126]=self.lift_shadow.value.clamp(0.,1.)*u[1];
  // Retained background recedes slightly with the same spring (including its small rebound).
  u[127]=1.-0.045*self.lift.value.clamp(0.,1.08);
  let Some(cell)=self.action_cell else{return};let world=self.action_base(cell);let target=self.lift_placement.card;
  u[23]=self.lift_slot.map(|v|v as f32).unwrap_or(-1.);
  u[4]=cell as f32;u[5]=self.song(cell)as f32;u[27]=if self.is_lifted(cell){1.}else{0.};u[28]=target.x;u[29]=target.y;u[30]=target.w;u[31]=target.h;
  let scale=target.w/world.w;let radius=(6.+(16./scale.max(0.01)-6.)*u[1]).min(world.w*0.5).min(world.h*0.5);u[21]=radius;
  u[6]=f32::INFINITY;u[7]=f32::INFINITY;u[8]=f32::NEG_INFINITY;u[9]=f32::NEG_INFINITY;
  for corner in 0..4{let(bx,by)=match corner{0=>(world.x+world.w-radius,world.y+radius),1=>(world.x+world.w-radius,world.y+world.h-radius),2=>(world.x+radius,world.y+world.h-radius),_=>(world.x+radius,world.y+radius)};
   for n in 0..8{// Fixed arc basis: no per-vertex trig or repeated world warp evaluation.
    let(c,s)=match n.min(3){0=>(0.,-1.),1=>(0.5,-0.8660254),2=>(0.8660254,-0.5),_=>(1.,0.)};
    let(c,s)=match corner{0=>(c,s),1=>(-s,c),2=>(-c,-s),_=>(s,-c)};let(mut wx,mut wy)=(bx+radius*c,by+radius*s);
    if n>3{let t=(n-3)as f32/5.;match corner{0=>wy+=(world.h-2.*radius)*t,1=>wx-=(world.w-2.*radius)*t,2=>wy-=(world.h-2.*radius)*t,_=>wx+=(world.w-2.*radius)*t}}
    let(x,y)=self.lift_point_in(world,wx,wy);let at=32+(corner*8+n)*2;u[at]=x;u[at+1]=y;u[6]=u[6].min(x);u[7]=u[7].min(y);u[8]=u[8].max(x);u[9]=u[9].max(y);
   }
  }
  let tray=self.lift_placement.menu;let slide=16.*(1.-self.menu.value);let gap=6.;let quick_w=(tray.w-gap*2.)/3.;
  for n in 0..4{let r=if n<3{Rect{x:tray.x+n as f32*(quick_w+gap),y:tray.y+slide,w:quick_w,h:66.}}else{Rect{x:tray.x,y:tray.y+72.+slide,w:tray.w,h:48.}};
   let at=96+n*4;u[at]=r.x;u[at+1]=r.y;u[at+2]=r.w;u[at+3]=r.h;u[10+n*2]=r.x+r.w*0.5;u[11+n*2]=r.y+r.h*0.5;
   u[112+n]=1.;u[116+n]=u[0];u[120+n]=4.;
   for(k,(x,y))in [(r.x,r.y),(r.x+r.w,r.y),(r.x+r.w,r.y+r.h),(r.x,r.y+r.h)].iter().enumerate(){u[128+n*16+k*2]=*x;u[129+n*16+k*2]=*y;}
  }
  u[18]=tray.x+tray.w*0.5;u[19]=tray.y+24.;
 }
}
