//! Scene, interaction, cache and lifecycle regression coverage.
use super::*;

 #[test]fn screen_edge_fades_follow_viewport_for_both_map_styles(){
  let mut map=State::new();let mut rhine=rhine::Rhine::new();let mut out=vec![0.;FLOATS];
  map.command(0,64,0.,0.);map.mode.jump(1.);
  rhine.command(0,64,0.,0.);rhine.common.mode.jump(1.);
  for (w,h) in [(400.,890.),(1024.,600.),(890.,400.),(400.,890.)]{
   for is_rhine in [false,true]{
    if is_rhine{rhine.frame(0.,w,h,&mut out);}else{map.frame(0.,w,h,&mut out);}
    if w>h{
     assert_eq!([out[26],out[27],out[30]],[0.,h,0.]);
     for y in [0.,1.,h/2.,h-1.,h]{assert_eq!(crate::render_policy::edge_factor(y,h,out[26],out[27]),0.);}
     if !is_rhine{for slot in 0..out[16]as usize{let o=HEADER+slot*STRIDE;
      if out[o+12]>0.{assert_eq!(out[o+6],1.,"landscape labels must not retain portrait edge fading");}
     }}
    }else{
     assert!(out[26]>0.&&out[27]<h);assert_eq!(out[30],1.);
     assert_eq!(crate::render_policy::edge_factor(0.,h,out[26],out[27]),1.);
     assert_eq!(crate::render_policy::edge_factor(h,h,out[26],out[27]),1.);
    }
   }
  }
 }

 #[test]fn stable_catalogue_and_shared_boundaries(){
  for i in 0..2000{let(x,y)=block(i);assert_eq!(block_index(x,y),i);}
  let mut s=State::new();assert!(s.command(0,96,0.,0.));
  for phase in 0..11{s.weights.clear();s.weights.insert(0,phase as f32/10.);s.weights.insert(17,1.-phase as f32/10.);s.rebuild_warps();
   let mut last=f32::NEG_INFINITY;for x in -1200..1200{let next=s.warp_axis(x as f32,true);assert!(next>last);last=next;}
   for i in 0..96{let a=s.live(i);assert!(a.w>=base(i).w-0.001&&a.h>=base(i).h-0.001);for j in 0..i{let b=s.live(j);assert!(a.x+a.w<=b.x+0.001||b.x+b.w<=a.x+0.001||a.y+a.h<=b.y+0.001||b.y+b.h<=a.y+0.001,"overlap {i} {j}");}}
  }
 }
 #[test]fn playback_handoff_does_not_zoom_camera(){
  let mut s=State::new();s.command(0,90,0.,0.);s.command(3,1,0.,0.);s.command(2,0,1.,0.);let mut out=vec![0.;FLOATS];
  for _ in 0..180{assert!(s.frame(1./60.,400.,674.,&mut out));}
  let large=s.live(0).w.min(s.live(0).h);assert!(large>210.);let zoom=s.zoom.value;
  s.command(1,2,0.,0.);assert_eq!(s.playing,Some(0));assert_eq!(s.zoom.target,zoom);
  s.command(2,2,1.,0.);s.frame(1./60.,400.,674.,&mut out);assert_eq!(out[24],1.);assert_eq!(out[25],1.);for _ in 0..180{s.frame(1./60.,400.,674.,&mut out);}
  assert!(s.live(2).w.min(s.live(2).h)>210.);assert!(s.weights.get(&0).copied().unwrap_or(0.)<0.001);assert_eq!(s.zoom.value,zoom);assert_eq!(out[14],0.);assert_eq!(out[24],0.);assert_eq!(out[25],0.);
 }
 #[test]fn picking_visibility_and_pagination(){
  let mut s=State::new();s.command(0,30,0.,0.);s.command(3,1,0.,0.);s.command(1,12,0.,0.);let mut out=vec![0.;FLOATS];
  for _ in 0..180{s.frame(1./60.,1024.,494.,&mut out);}
  let(x,y)=s.center(12);let(px,py)=s.project(x,y);assert_eq!(s.hit(px,py),12);assert_eq!(out[14],0.);assert!(out[16]<=VISIBLE as f32);
  let fixed=base(12);s.command(0,10_000,0.,0.);s.frame(1./60.,1024.,494.,&mut out);assert_eq!(base(12).x,fixed.x);assert!(out[16]<=VISIBLE as f32);
  s.command(3,0,0.,0.);for _ in 0..180{s.frame(1./60.,1024.,494.,&mut out);}assert_eq!(out[1],0.);assert_eq!(out[18],1.);assert_eq!(s.hit(px,py),-1);
 }
 #[test]fn rectangular_variation_is_restrained(){
  let mut rectangles=0;
  for i in 0..32{let r=base(i);let ratio=r.w.max(r.h)/r.w.min(r.h);
   assert!(ratio<=1.85);assert!(r.w.min(r.h)>90.);if ratio>1.12{rectangles+=1;}}
  assert!(rectangles>=20);
 }
 #[test]fn stable_frames_reuse_geometry_and_preload_neighbors(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];
  s.command(0,1024,0.,0.);s.command(3,1,0.,0.);
  for _ in 0..220{s.frame(1./120.,400.,674.,&mut out);}
  // Settle fully, then a cover completion must not rebuild/sort geometry.
  for _ in 0..220{s.frame(1./120.,400.,674.,&mut out);}
  let revision=out[23];let span=out[31];assert!(span>0.);let storage=(s.nearby.as_ptr(),s.candidates.as_ptr(),s.visible.as_ptr());
  s.frame(1./120.,400.,674.,&mut out);assert_eq!(revision,out[23]);assert_eq!(span,out[31]);assert!(out[22]>0.);
  s.command(0,100_000,0.,0.);s.frame(1./120.,400.,674.,&mut out);
  assert!(s.nearby.len()<1024);assert_eq!(storage,(s.nearby.as_ptr(),s.candidates.as_ptr(),s.visible.as_ptr()));
  let n=out[16]as usize;let ahead=out[22]as usize;assert!(ahead<=PREFETCH);
  for i in 0..ahead{let index=out[HEADER+STRIDE*SLOTS+i]as usize;assert!(index<100_000);
   assert!(!(0..n).any(|j|out[HEADER+j*STRIDE]as usize==index));}
 }
 #[test]fn pinch_has_light_follow_and_rebounds_at_both_edges(){
  let mut s=State::new();s.command(0,1024,0.,0.);let mut out=vec![0.;FLOATS];
  s.command(11,0,0.,0.);s.command(12,0,1.5,0.);assert!((s.zoom.value-1.485).abs()<0.0001);
  for _ in 0..12{s.command(12,0,1.4,0.);}assert!(s.zoom.value>ZOOM_MAX&&s.zoom.value<ZOOM_MAX+0.33);
  s.command(13,0,0.,0.);for _ in 0..240{s.frame(1./120.,400.,674.,&mut out);}assert_eq!(s.zoom.value,ZOOM_MAX);
  s.command(11,0,0.,0.);for _ in 0..20{s.command(12,0,0.7,0.);}assert!(s.zoom.value<ZOOM_MIN&&s.zoom.value>0.2);
  s.command(13,0,0.,0.);for _ in 0..240{s.frame(1./120.,400.,674.,&mut out);}assert_eq!(s.zoom.value,ZOOM_MIN);
 }
 #[test]fn selection_does_not_show_floating_play_control(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
  for _ in 0..440{s.frame(1./120.,700.,890.,&mut out);}
  // Tap focuses a different, nonplaying cell without creating a play overlay or starting it.
  s.command(1,1,0.,0.);
  for _ in 0..180{s.frame(1./120.,700.,890.,&mut out);assert_eq!(out[14],0.);assert_eq!(s.playing,Some(0));}
  let(x,y)=s.center(1);let(px,py)=s.project(x,y);assert_eq!(s.hit(px,py),1);
  assert!(s.command(15,1,0.,0.));s.frame(1./120.,700.,890.,&mut out);
  assert_eq!(s.action_cell,Some(1));assert_eq!(s.action_phase,1);assert_eq!(s.playing,Some(0));
 }
 #[test]fn repeating_map_stays_populated_and_pins_clicked_instance(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];
  s.command(0,3,0.,0.);s.command(3,1,0.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  for (x,y) in [(-12000.,0.),(24000.,0.),(-12000.,-12000.),(0.,24000.)]{
   s.command(4,0,x,y);s.frame(1./120.,400.,890.,&mut out);
   assert!(out[16]>5.);assert!(s.nearby.len()<1024);
   for n in 0..out[16]as usize{assert!(out[HEADER+n*STRIDE]<3.);}
  }
  let(cell,_)=s.visible[0];let song=s.song(cell);
  assert!(cell>=3);s.command(1,cell as i32,0.,0.);s.command(2,song as i32,1.,0.);
  assert_eq!(s.playing,Some(cell));assert_eq!(s.selected,cell);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  assert!(s.live(cell).w.min(s.live(cell).h)>210.);
  let bindings=s.bindings.clone();s.command(0,1024,0.,0.);s.frame(1./120.,400.,890.,&mut out);
  for(&cell,&song)in &bindings{if let Some(next)=s.bindings.get(&cell){assert_eq!(*next,song);}}
  assert_eq!(s.song(s.selected),song);assert_eq!(out[11],song as f32);
  s.command(1,-1,0.,0.);assert_eq!(s.selected,cell);
  let count=out[16]as usize;let ahead=out[22]as usize;
  for n in 0..ahead{let song=out[HEADER+STRIDE*SLOTS+n];
   assert!(!(0..count).any(|i|out[HEADER+i*STRIDE]==song));
   assert!(!(0..n).any(|i|out[HEADER+STRIDE*SLOTS+i]==song));
  }
 }
 #[test]fn camera_gestures_keep_caption_alpha_and_geometry(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];
  s.command(0,1024,0.,0.);s.mode.jump(1.);
  s.frame(0.,400.,890.,&mut out);
  let start=(out[2],out[3]);let mut saw_motion=false;
  for tick in 0..240{
   match tick{
    0..=59=>{s.command(4,0,12.,-6.);}
    60=>{s.command(5,0,1800.,-900.);}
    100..=129=>{s.command(4,0,-16.,8.);}
    130=>{s.command(5,0,-1800.,900.);}
    160=>{s.command(11,0,0.,0.);}
    161..=179=>{s.command(12,0,1.01,0.);}
    180=>{s.command(13,0,0.,0.);}
    _=>{}
   }
   assert!(s.frame(1./120.,400.,890.,&mut out));
   saw_motion|=out[24]>0.;
   assert_eq!(out[15],1.,"camera motion hid captions at frame {tick}");
   assert!((0..out[16]as usize).any(|n|{
    let o=HEADER+n*STRIDE;out[o+6]>0.1&&out[o+12]>0.&&out[o+13]>0.
   }),"visible caption geometry missing at frame {tick}");
  }
  assert!(saw_motion);assert_ne!(start,(out[2],out[3]));
 }
 #[test]fn caption_layout_metrics_ignore_camera_but_follow_card_geometry(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];
  s.command(0,1024,0.,0.);s.mode.jump(1.);s.frame(0.,700.,890.,&mut out);
  let mut metrics=HashMap::new();
  let mut projected=HashMap::new();
  let mut changed_projection=false;let mut compared=0;
  for tick in 0..80{
   s.command(4,0,3.,-2.);
   if tick==40{s.command(6,0,1.2,0.);}
   s.frame(1./120.,700.,890.,&mut out);
   for slot in 0..out[16]as usize{
    let o=HEADER+slot*STRIDE;let cell=out[o+7]as usize;let m=MAP_CAPTIONS+slot*16;
    let native=[out[m],out[m+1],out[m+2]];
    let(_,_,w,h,_,font)=caption_region(s.live(cell));assert_eq!(native,[w,h,font]);
    if let Some(previous)=metrics.insert(cell,native){assert_eq!(previous,native);compared+=1;}
    if let Some(previous)=projected.insert(cell,out[o+12]){changed_projection|=previous!=out[o+12];}
   }
  }
  assert!(compared>100&&changed_projection);
  // A real card expansion still publishes its new native text region.
  s.command(2,0,1.,0.);
  for _ in 0..180{s.frame(1./120.,700.,890.,&mut out);}
  let slot=s.visible.iter().position(|(cell,_)|*cell==s.playing.unwrap()).unwrap();
  let m=MAP_CAPTIONS+slot*16;let(_,_,w,h,_,font)=caption_region(s.live(s.playing.unwrap()));
  assert_eq!([out[m],out[m+1],out[m+2]],[w,h,font]);
  assert_ne!(metrics[&0],[w,h,font]);
 }
 #[test]fn hidden_caption_geometry_is_deferred_and_restored(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];
  s.command(0,1024,0.,0.);s.command(3,1,0.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  let capacity=s.weights.capacity();s.command(2,0,1.,0.);
  let mut saw_hidden=false;
  for _ in 0..440{
   s.frame(1./120.,400.,890.,&mut out);
   if out[1]>=0.95&&out[15]<0.1{
    saw_hidden=true;
    for n in 0..out[16]as usize{let o=HEADER+n*STRIDE;assert_eq!(out[o+12],0.);assert_eq!(out[o+13],0.);}
   }
  }
  assert!(saw_hidden);assert_eq!(capacity,s.weights.capacity());
  assert!(out[15]>=0.1);
  assert!((0..out[16]as usize).any(|n|out[HEADER+n*STRIDE+12]>0.));
  let revision=out[23];s.frame(1./120.,400.,890.,&mut out);assert_eq!(revision,out[23]);
 }
 #[test]fn expansion_candidates_reuse_identical_projection(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.command(3,1,0.,0.);
  for tick in 0..90{
   if tick%15==0{s.command(2,(tick/15)*3,1.,0.);}
   assert!(s.frame(1./120.,400.,890.,&mut out));
   for &(_,r,_,sx,sy,radius,scale,_) in &s.candidates{
    let(x,y,expected_scale)=s.projected(r.x+r.w/2.,r.y+r.h/2.);assert_eq!(scale.to_bits(),expected_scale.to_bits());assert_eq!(sx.to_bits(),x.to_bits());assert_eq!(sy.to_bits(),y.to_bits());
    assert_eq!(radius.to_bits(),((r.w+r.h)*s.zoom.value*0.55).to_bits());
   }
  }
 }
 #[test]fn shared_edge_cache_matches_original_warp_during_interruptions(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,100_000,0.,0.);s.command(3,1,0.,0.);
  for tick in 0..80{
   if tick%8==0{s.command(2,tick/2,1.,0.);}
   if tick%13==0{s.command(4,0,180.,-100.);}
   let(w,h)=if tick<40{(400.,890.)}else{(1200.,800.)};s.frame(1./120.,w,h,&mut out);
   assert!(s.edge_x.len()+s.edge_y.len()<s.nearby.len()*4);
   for(n,&(_,base))in s.nearby.iter().enumerate(){
    let a=s.cached_rect(n);let b=s.warp_rect(base);
    assert_eq!([a.x.to_bits(),a.y.to_bits(),a.w.to_bits(),a.h.to_bits()],[b.x.to_bits(),b.y.to_bits(),b.w.to_bits(),b.h.to_bits()]);
   }
  }
 }
 #[test]fn locate_targets_playback_and_absence_does_not_move_selection(){
  let mut s=State::new();s.command(0,1024,0.,0.);s.command(1,7,0.,0.);
  assert!(!s.command(14,0,0.,0.));assert_eq!(s.selected,7);
  s.command(2,2,0.,0.);s.command(1,7,0.,0.);assert!(s.command(14,0,0.,0.));
  assert_eq!(s.selected,s.playing.unwrap());assert_eq!(s.song(s.selected),2);assert!(!s.running);
  s.command(2,-1,0.,0.);s.command(1,7,0.,0.);assert!(!s.command(14,0,0.,0.));assert_eq!(s.selected,7);
 }
 #[test]fn inputs_and_picking_do_not_wait_on_geometry_lock(){
  let id=create();let handle=entry(id).unwrap();let geometry=handle.scene.lock().unwrap();
  let(tx,rx)=std::sync::mpsc::channel();
  let worker=std::thread::spawn(move||{tx.send((command(id,0,1024,0.,0.),command(id,3,1,0.,0.),hit(id,20.,20.))).unwrap();});
  let result=rx.recv_timeout(std::time::Duration::from_secs(1));
  drop(geometry);worker.join().unwrap();assert_eq!(result.unwrap(),(true,true,-1));
  let mut out=vec![0.;FLOATS];assert!(step(id,0.1,400.,890.,&mut out));assert!(out[16]>0.);
  assert_eq!(handle.scene.lock().unwrap().count,1024);
  release(id);assert!(!step(id,0.1,400.,890.,&mut out));
 }
 #[test]fn bounded_priority_selection_matches_full_sort(){
  let mut synthetic=Vec::new();
  for n in 0..2000{synthetic.push((n,Rect::default(),((n*173)%631)as f32,0.,0.,0.,1.,n));}
  let mut indices:Vec<_>=(0..synthetic.len()).collect();let mut expected=indices.clone();
  expected.sort_unstable_by(|a,b|candidate_order(&synthetic[*a],&synthetic[*b]));expected.truncate(VISIBLE);
  nearest_indices(&mut indices,&synthetic,VISIBLE);assert_eq!(indices,expected);
  for count in [3,1024,100_000]{for zoom in [0.45,1.,3.]{
   let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,count,0.,0.);s.mode.jump(1.);s.zoom.jump(zoom);
   for tick in 0..32{
    if tick%8==0{s.command(2,(tick/8)%count,1.,0.);}s.frame(1./120.,1200.,900.,&mut out);
    let mut old=s.candidates.clone();old.sort_unstable_by(candidate_order);
    let visible:Vec<_>=old.iter().filter(|c|c.3+c.5>=0.&&c.3-c.5<=1200.&&c.4+c.5>=0.&&c.4-c.5<=900.).take(VISIBLE).collect();
    assert_eq!(s.visible.iter().map(|v|v.0).collect::<Vec<_>>(),visible.iter().map(|c|c.0).collect::<Vec<_>>());
    let mut seen:HashSet<_>=visible.iter().map(|c|c.7).collect();
    let ahead:Vec<_>=old.iter().filter_map(|c|if seen.insert(c.7){Some(c.7)}else{None}).take(PREFETCH).collect();
    assert_eq!(ahead.len(),out[22]as usize);
    for(n,song)in ahead.iter().enumerate(){assert_eq!(*song,out[HEADER+STRIDE*SLOTS+n]as usize);}
   }
  }}
 }
 #[test]fn expanded_portrait_caption_stays_in_solid_panel_and_fits(){
  let r=Rect{x:0.,y:0.,w:220.,h:295.4};let(_,y,w,h,over,scale)=caption_region(r);
  assert!(!over);assert!(y>=r.w);assert!(46.*scale<=h+0.001);assert!(48.*scale<=w+0.001);
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);
  let index=(0..32).find(|&i|{let r=base(i);r.h-r.w>=64.}).unwrap();s.command(2,index as i32,1.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  let n=s.visible.iter().position(|v|v.0==index).unwrap();let o=HEADER+n*STRIDE;
  assert_eq!(out[o+15],0.);assert!(out[o+12]>0.);assert!(out[o+13]>=32.*out[o+14]);assert!(out[15]>0.9);
 }
 #[test]fn lift_confirmation_dust_and_refill_preserve_geometry(){
  for(w,h)in [(400.,890.),(1200.,800.),(800.,1200.)]{
   let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
   for _ in 0..440{s.frame(1./120.,w,h,&mut out);}let card=s.live(0);let zoom=s.zoom.value;
   assert!(!s.command(15,-1,0.,0.));assert!(s.command(15,0,0.,0.));
   for _ in 0..440{s.frame(1./120.,w,h,&mut out);}
   assert_eq!(s.action_phase,1);assert_eq!(s.lift.value,1.);assert_eq!(s.menu.value,1.);
   assert_eq!(s.live(0).w.to_bits(),card.w.to_bits());assert_eq!(s.live(0).h.to_bits(),card.h.to_bits());
   for n in 0..4{assert!(out[ACTION+10+n*2]>=26.&&out[ACTION+10+n*2]<=w-26.);assert!(out[ACTION+11+n*2]>=76.&&out[ACTION+11+n*2]<=h-210.);}
   let mut previous=f32::NEG_INFINITY;for x in -1000..1000{let next=s.warp_axis(x as f32,true);assert!(next>previous);previous=next;}
   assert!(s.command(17,0,0.,0.));s.frame(1./120.,w,h,&mut out);assert_eq!(s.confirmation.value,0.);
   for _ in 0..440{s.frame(1./120.,w,h,&mut out);}assert_eq!(s.menu.value,0.);assert_eq!(s.confirmation.value,1.);
   assert!(s.command(18,0,0.,0.));s.frame(1./120.,w,h,&mut out);
   let slot=s.visible.iter().position(|v|v.0==0).unwrap();assert_eq!(out[HEADER+slot*STRIDE+9],0.);
   s.command(20,0,0.,0.);s.command(19,0,0.,0.);
   for _ in 0..440{s.frame(1./120.,w,h,&mut out);}
   assert_eq!(s.count,1023);assert_eq!(s.playing,None);assert_eq!(s.action_cell,None);assert_eq!(s.zoom.value,zoom);assert_eq!(s.fill_cell,None);
   assert_eq!(s.live(0).w.to_bits(),base(0).w.to_bits());
  }
 }
 #[test]fn lift_cancel_and_track_change_keep_last_intent(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  s.command(15,0,0.,0.);s.frame(0.08,400.,890.,&mut out);s.command(16,0,0.,0.);s.command(15,0,0.,0.);s.frame(0.08,400.,890.,&mut out);
  assert_eq!(s.action_phase,1);assert!(s.lift.value>0.);s.command(2,1,1.,0.);assert_eq!(s.action_phase,1);assert_eq!(s.action_cell,Some(0));s.command(16,0,0.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}assert_eq!(s.action_cell,None);assert_eq!(s.playing,Some(1));assert_eq!(s.zoom.value,1.);
 }
 #[test]fn catalogue_removal_unlocks_without_particle_callback(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  s.command(15,0,0.,0.);for _ in 0..160{s.frame(1./120.,400.,890.,&mut out);}
  s.command(17,0,0.,0.);for _ in 0..160{s.frame(1./120.,400.,890.,&mut out);}
  s.command(18,0,0.,0.);s.frame(1./120.,400.,890.,&mut out);assert_eq!(s.action_phase,3);
  // Reproduce persisted feedback/catalogue removal, deliberately omit Java command 19.
  s.command(20,0,0.,0.);s.command(2,-1,0.,0.);assert_eq!(s.action_phase,0);assert_eq!(s.hidden_cell,None);
  let x=s.x.value;s.command(4,0,40.,0.);assert!(s.x.value>x);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}assert_eq!(s.action_cell,None);assert_eq!(s.fill_cell,None);
  s.command(19,0,0.,0.);s.frame(1./120.,400.,890.,&mut out);assert_eq!(s.fill_cell,None);
 }
 #[test]fn nonplaying_card_actions_keep_audio_owner(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}
  let cell=s.visible.iter().find(|v|v.0!=0).unwrap().0;let song=s.song(cell);
  assert!(s.command(15,cell as i32,0.,0.));assert_eq!(s.playing,Some(0));assert_eq!(s.selected,cell);
  for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}assert_eq!(out[ACTION+5],song as f32);
  s.command(17,0,0.,0.);for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}
  s.command(18,0,0.,0.);s.command(20,song as i32,0.,0.);
  assert_eq!(s.playing,Some(0));assert_eq!(s.action_phase,0);assert!(s.running);
  for _ in 0..440{s.frame(1./120.,400.,890.,&mut out);}assert_eq!(s.weights.get(&0),Some(&1.));
 }
 #[test]fn capture_latches_pose_without_waiting_for_global_idle(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
  for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}s.command(15,0,0.,0.);
  for _ in 0..20{s.frame(1./120.,400.,890.,&mut out);}s.command(17,0,0.,0.);
  s.frame(1./120.,400.,890.,&mut out);assert!(out[0]>0.);
  let pose=(s.x.value,s.y.value,s.zoom.value,s.lift.value);
  assert!(s.command(21,0,0.,0.));
  for _ in 0..10{s.frame(0.1,400.,890.,&mut out);assert_eq!(out[0],0.);assert_eq!(out[ACTION+26],1.);assert_eq!(pose,(s.x.value,s.y.value,s.zoom.value,s.lift.value));}
  assert!(s.command(18,0,0.,0.));s.frame(1./120.,400.,890.,&mut out);assert!(!s.capture_hold);assert_eq!(s.hidden_cell,Some(0));
  s.command(19,0,0.,0.);assert!(!s.capture_hold);
 }
 #[test]fn invalid_and_released_handles_are_safe(){
  let id=create();assert!(id>0);assert!(!command(id,0,-1,0.,0.));assert!(!command(id,4,0,f32::NAN,0.));let mut short=[0.;2];assert!(!step(id,0.,400.,800.,&mut short));release(id);assert!(!command(id,0,10,0.,0.));assert_eq!(hit(id,0.,0.),-1);release(id);
 }

 #[test]fn lift_preserves_world_and_camera_and_only_recomposes_backdrop(){
  for(w,h)in [(400.,890.),(1200.,800.),(800.,1200.),(1024.,494.)]{
   let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);s.command(2,0,1.,0.);
   for _ in 0..440{s.frame(1./120.,w,h,&mut out);}
   let camera=(s.x.value,s.y.value,s.zoom.value);let revision=s.world_revision;
   let before:Vec<_>=s.nearby.iter().map(|&(i,_)|(i,s.live(i))).collect();
   s.command(15,0,0.,0.);
   for _ in 0..240{s.frame(1./120.,w,h,&mut out);assert_eq!(camera,(s.x.value,s.y.value,s.zoom.value));assert_eq!(revision,s.world_revision);}
   for(i,r)in before{let now=s.live(i);assert_eq!((r.x,r.y,r.w,r.h),(now.x,now.y,now.w,now.h));}
   let world=s.live(0);let t=s.lift_placement.card;let menu=s.lift_placement.menu;
   assert!((t.w/t.h-world.w/world.h).abs()<0.001,"card must scale uniformly");
   assert!(t.x>=20.&&t.y>=76.&&t.x+t.w<=w-19.&&t.y+t.h<=h-209.);
   if h<600.&&w>600.{assert!(menu.x>=t.x+t.w+13.);}else{assert!(menu.y>=t.y+t.h+13.);}
   for n in 0..4{let a=ACTION+96+n*4;assert!(out[a+2]>=64.&&out[a+3]>=48.);assert!(out[a]>=19.&&out[a]+out[a+2]<=w-19.&&out[a+1]+out[a+3]<=h-209.);}
   assert_eq!(out[ACTION+27],1.);assert_eq!(out[ACTION+125],VERSION as f32);
   let(a,b)=s.lift_point(world.x,world.y);let(c,e)=s.lift_point(world.x+world.w,world.y+world.h);
   assert!((a-t.x).abs()<0.001&&(b-t.y).abs()<0.001&&(c-t.x-t.w).abs()<0.001&&(e-t.y-t.h).abs()<0.001);
  }
 }
 #[test]fn removal_refills_world_without_lifting_the_replacement(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,2,0.,0.);s.mode.jump(1.);
  for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}s.command(15,0,0.,0.);
  for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}s.command(17,0,0.,0.);s.command(18,0,0.,0.);s.command(20,0,0.,0.);
  s.frame(1./120.,400.,890.,&mut out);assert_eq!(out[ACTION+27],0.);assert!(out[ACTION+1]>0.);assert_eq!(s.action_phase,0);
 }

 #[test]fn lift_spring_rebounds_subtly_and_preserves_reversal_velocity(){
  for hz in [60.,90.,120.]{let mut spring=Spring::new(0.);spring.target=1.;let mut peak=0_f32;
   for _ in 0..hz as usize{spring.step_lift(1./hz);peak=peak.max(spring.value);}
   assert!(peak>1.02&&peak<1.05,"small physical rebound: {peak}");assert_eq!(spring.value,1.);
  }
  let mut a=Spring::new(0.);a.target=1.;let mut b=a;
  a.step_lift(0.1);for _ in 0..12{b.step_lift(1./120.);}assert!((a.value-b.value).abs()<0.00001);assert!(a.value>0.6);
  let(value,velocity)=(a.value,a.velocity);a.target=0.;a.step_lift(0.);assert_eq!(a.value,value);assert_eq!(a.velocity,velocity);
  for _ in 0..240{a.step_lift(1./120.);}assert_eq!(a.value,0.);
 }
 #[test]fn background_scale_follows_lift_without_invalidating_world(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);
  for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}let world=s.world_revision;s.command(15,0,0.,0.);let mut min_scale=1_f32;
  for _ in 0..180{s.frame(1./120.,400.,890.,&mut out);min_scale=min_scale.min(out[ACTION+127]);assert_eq!(world,s.world_revision);}
  assert!(min_scale<0.955&&min_scale>0.95);assert!((out[ACTION+127]-0.955).abs()<0.000001);
  s.command(16,0,0.,0.);for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}assert_eq!(out[ACTION+127],1.);
 }

 #[test]fn lifted_caption_is_directly_indexed_and_background_geometry_stays_recordable(){
  let mut s=State::new();let mut out=vec![0.;FLOATS];s.command(0,1024,0.,0.);s.mode.jump(1.);
  for _ in 0..240{s.frame(1./120.,400.,890.,&mut out);}s.command(15,0,0.,0.);
  for _ in 0..160{s.frame(1./120.,400.,890.,&mut out);
   let slot=out[ACTION+23]as usize;assert_eq!(out[HEADER+slot*STRIDE+7],out[ACTION+4]);
   // No per-caption fading/re-recording: a single background RenderNode alpha owns it.
   for n in 0..out[16]as usize{if n==slot{continue}let at=HEADER+n*STRIDE;
    assert_eq!(out[at+6],s.cache[at+6]);assert_eq!(&out[at+10..at+15],&s.cache[at+10..at+15]);
   }
  }
  s.command(17,0,0.,0.);s.command(21,0,0.,0.);s.frame(0.,400.,890.,&mut out);
  for n in 0..32{let x=out[ACTION+32+n*2];let y=out[ACTION+33+n*2];assert!(x>=out[ACTION+6]&&x<=out[ACTION+8]&&y>=out[ACTION+7]&&y<=out[ACTION+9]);}
  let world=s.world_revision;s.command(18,0,0.,0.);s.frame(1./120.,400.,890.,&mut out);
  let slot=out[ACTION+23]as usize;assert_eq!(out[HEADER+slot*STRIDE+9],0.);assert_eq!(s.world_revision,world);
 }

 #[test]fn portrait_free_drag_public_ingress_restores_peak_and_locate_returns_gpu_pose(){
  let id=create();let mut f=vec![0.;FLOATS];let mut bindings=vec![0;SLOTS*5];for n in 0..SLOTS{bindings[n*5]=n as i32;}let mut gpu=vec![0.;SLOTS*rhine_gpu::INSTANCE];
  assert!(command(id,10,1024,0.,0.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  let slot=|f:&[f32]|(0..f[16]as usize).find(|&n|f[HEADER+n*STRIDE+7]==0.).unwrap();
  let start=(f[RHINE_POSES+slot(&f)*16+12],f[RHINE_POSES+slot(&f)*16+13]);
  assert!(!command(id,27,0,0.,f32::NAN));
  let mut total=(0.,0.);
  for (dx,dy) in [(30.,0.),(0.,40.),(-60.,0.),(0.,-80.)]{
   assert!(command(id,26,0,0.,0.));assert!(command(id,27,0,dx,dy));
   // Dragging now retracts playback; only release restores its peak/emission.
   // Keep checking the same public command -> published pose -> GPU path.
   for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
   // It may be a low neighbour of the independent inspection hill; it must
   // lose its playback-peak emission, not be forced to an unlit flat position.
   assert!(f[HEADER+slot(&f)*STRIDE+5]<0.5);
   assert!(command(id,28,1,0.,0.));for _ in 0..300{assert!(step(id,1./120.,400.,890.,&mut f));}
   total.0+=dx;total.1+=dy;let n=slot(&f);
   assert!((f[RHINE_POSES+n*16+12]-start.0-total.0).abs()<0.02);
   assert!((f[RHINE_POSES+n*16+13]-start.1-total.1).abs()<0.02);
   assert_eq!(f[ACTION+22],0.);assert_eq!(f[11],0.);assert_eq!(f[0],0.);assert_eq!(f[HEADER+n*STRIDE+5],1.);
   assert!(rhine_gpu::pack(&f,&bindings,&mut gpu,false).is_some());
   // GPU depth order is independent of snapshot slot order. Follow the binding
   // identity and verify the entire pose, not the old ordinal in the draw stream.
   let instance=gpu[..f[16]as usize*rhine_gpu::INSTANCE].chunks_exact(rhine_gpu::INSTANCE).find(|v|v[16]==n as f32).unwrap();
   assert_eq!(&instance[..16],&f[RHINE_POSES+n*16..RHINE_POSES+(n+1)*16]);
  }
  assert!(command(id,14,0,0.,0.));for _ in 0..240{assert!(step(id,1./120.,400.,890.,&mut f));}
  let n=slot(&f);assert!((f[RHINE_POSES+n*16+12]-start.0).abs()<0.001&&(f[RHINE_POSES+n*16+13]-start.1).abs()<0.001);
  assert_eq!(f[0],0.);release(id);
 }
 #[test]fn portrait_append_exit_and_reentry_preserve_the_playback_lane(){
  let id=create();let mut f=vec![0.;FLOATS];
  assert!(command(id,10,1024,1022.,1022.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  for song in [1023,0]{assert!(command(id,2,song,1.,0.));for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}}
  assert_eq!(f[ACTION+22]as usize,block_index(0,2));
  // The resident 0/1/2 covers were wrapped under 1024, not the new modulus 1280.
  assert!(command(id,0,1280,0.,0.));assert!(step(id,0.,400.,890.,&mut f));
  assert!(command(id,2,1,1.,0.));assert!(step(id,0.,400.,890.,&mut f));
  assert_eq!(f[ACTION+22]as usize,block_index(0,3));
  assert!(command(id,3,0,0.,0.));for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert!(command(id,2,2,1.,0.));assert!(command(id,3,1,0.,0.));
  for _ in 0..240{assert!(step(id,1./120.,400.,890.,&mut f));}
  let cell=block_index(0,4);assert_eq!(f[ACTION+22]as usize,cell);
  let slot=(0..f[16]as usize).find(|&n|f[HEADER+n*STRIDE+7]==cell as f32).expect("reentered playing cover must be visible");
  assert_eq!(f[HEADER+slot*STRIDE]as usize,2);assert_eq!(f[HEADER+slot*STRIDE+5],1.);
  assert_eq!(f[0],0.);release(id);
 }

 #[test]fn portrait_late_playback_and_menu_return_keep_advancing_after_reentry(){
  let id=create();let mut f=vec![0.;FLOATS];
  assert!(command(id,10,1024,0.,-1.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert!(command(id,2,1,1.,0.));for _ in 0..240{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert!(command(id,3,0,0.,0.));for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert!(command(id,2,1,1.,0.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  let cell=f[ACTION+22]as usize;let(c,r)=block(cell);let next=block_index(c,r+1);
  assert!(command(id,15,next as i32,0.,0.));for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert_eq!(f[ACTION+27],1.);assert!(command(id,16,0,0.,0.));assert!(command(id,2,2,1.,0.));
  let revision=f[23];for _ in 0..30{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert_ne!(f[23],revision);assert!(f[RHINE+1]<1.);assert_eq!(f[ACTION+22]as usize,next);
  for _ in 0..210{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert_eq!(f[ACTION+27],0.);let slot=(0..f[16]as usize).find(|&n|f[HEADER+n*STRIDE+7]==next as f32).unwrap();
  assert_eq!(f[HEADER+slot*STRIDE+5],1.);assert_eq!(f[0],0.);release(id);
 }


 #[test]fn portrait_public_playback_follows_after_a_drag_and_defers_only_while_touching(){
  let id=create();let mut f=vec![0.;FLOATS];assert!(command(id,10,1024,0.,0.));assert!(command(id,3,1,0.,0.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert!(command(id,26,0,0.,0.));assert!(command(id,27,0,160.,180.));
  for _ in 0..180{assert!(step(id,1./120.,400.,890.,&mut f));}
  let slot=|f:&[f32],cell:usize|(0..f[16]as usize).find(|&n|f[HEADER+n*STRIDE+7]==cell as f32).unwrap();
  let held_x=f[RHINE_POSES+slot(&f,0)*16+12];
  assert!(command(id,2,12,1.,0.));for _ in 0..60{assert!(step(id,1./120.,400.,890.,&mut f));}
  assert!((f[RHINE_POSES+slot(&f,0)*16+12]-held_x).abs()<0.001,"playback must not fight an active finger");
  assert!(command(id,28,1,0.,0.));for _ in 0..240{assert!(step(id,1./120.,400.,890.,&mut f));}
  let n=slot(&f,f[ACTION+22]as usize);assert_eq!(f[HEADER+n*STRIDE]as usize,12);assert_eq!(f[HEADER+n*STRIDE+5],1.);
  assert!((f[RHINE_POSES+n*16+12]-200.).abs()<48.01&&(f[RHINE_POSES+n*16+13]-445.).abs()<89.01);
  // A later song change must remain responsive, with no locate-button prerequisite.
  assert!(command(id,2,40,1.,0.));for _ in 0..240{assert!(step(id,1./120.,400.,890.,&mut f));}
  let n=slot(&f,f[ACTION+22]as usize);assert_eq!(f[HEADER+n*STRIDE]as usize,40);assert_eq!(f[HEADER+n*STRIDE+5],1.);
  assert!((f[RHINE_POSES+n*16+12]-200.).abs()<48.01&&(f[RHINE_POSES+n*16+13]-445.).abs()<89.01);assert_eq!(f[0],0.);release(id);
 }

#[test]
fn visible_wall_card_accepts_input_during_entry() {
 let id=create();
 let mut frame=vec![0.;FLOATS];
 command(id,30,2,0.,0.);
 command(id,10,1000,0.,-1.);
 command(id,3,1,0.,0.);
 step(id,1./60.,800.,480.,&mut frame);
 let mode=frame[1];
 assert!(mode>0. && mode<0.95);
 let slot=(0..frame[16] as usize).find(|&i|frame[HEADER+i*STRIDE+7]==0.).unwrap();
 let o=HEADER+slot*STRIDE;
 let ax=(frame[o+1]+frame[o+3]*0.5+frame[2])*frame[4]/frame[5];
 let ay=(frame[o+2]+frame[o+4]*0.5+frame[3])*frame[4]/frame[6];
 let p=1500./(1500.-frame[5]*(ax.cos()-1.)-frame[6]*(ay.cos()-1.));
 let list_y=frame[20]+35.-frame[21];
 let x=69.+mode*(frame[7]+frame[5]*ax.sin()*p-69.);
 let y=list_y+mode*(frame[8]+frame[6]*ay.sin()*p-list_y);
 assert_eq!(hit(id,x,y),0);
 release(id);
}
