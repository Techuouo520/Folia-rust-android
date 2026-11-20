//! Scheduling and scene depth policy. No fixed refresh rate and no elapsed-frame queue.
pub const DRAW:i32=0;
pub const WAIT_CONSUMER:i32=1;
pub const EXPIRED:i32=2;
pub fn submission(frame:i64,deadline:i64,now:i64,last:i64,_cost:i64,waiting:bool)->i32{
    if frame<=0||frame<=last{return EXPIRED}
    if waiting{return WAIT_CONSUMER}
    // Discard an already obsolete snapshot. Do not reject a current frame merely because a
    // previous draw was slow: that feedback loop could halve cadence indefinitely.
    if deadline>0 && now>=deadline{return EXPIRED}
    DRAW
}
pub fn upload_allowance(deadline:i64,now:i64,cost:i64)->i32{
    if deadline<=0 || deadline.saturating_sub(now)>cost.clamp(100_000,8_000_000)+500_000{1}else{0}
}
pub fn draw_cost(previous:i64,sample:i64)->i64{
    let sample=sample.clamp(100_000,8_000_000);
    if previous<=0{sample}else{(previous*3+sample)/4}
}
pub fn loudness_ramp(from:i32,to:i32,elapsed:i64,duration:i64)->i32{
    if duration<=0{return to}
    let t=(elapsed.max(0) as f32/duration as f32).clamp(0.,1.);
    (from as f32+(to as f32-from as f32)*t+0.5).floor() as i32
}
/// Viewport orientation, independent of whether the user selected Map or Rhine.
pub fn screen_edges(w:f32,h:f32)->[f32;3]{
    if w>h{[0.,h,0.]}else{[(h*0.18).clamp(56.,120.),h-(h*0.23).clamp(72.,152.),1.]}
}
pub fn edge_factor(y:f32,h:f32,top:f32,bottom:f32)->f32{
    if top<=0.&&bottom>=h{return 0.}
    fn smooth(x:f32)->f32{let x=x.clamp(0.,1.);x*x*(3.-2.*x)}
    smooth(1.-y/top.max(1.)).max(smooth((y-bottom)/(h-bottom).max(1.)))
}
#[cfg(test)]mod tests{
 use super::*;
 #[test]fn latest_frame_and_consumer_backpressure(){
  assert_eq!(submission(10,30,11,9,100_000,true),WAIT_CONSUMER);
  assert_eq!(submission(10,0,11,10,100_000,false),EXPIRED);
  assert_eq!(submission(20_000_000,27_000_000,21_000_000,10_000_000,1_000_000,false),DRAW);
  assert_eq!(submission(20_000_000,27_000_000,27_000_001,10_000_000,1_000_000,false),EXPIRED);
  assert_eq!(submission(20_000_000,27_000_000,26_500_000,10_000_000,8_000_000,false),DRAW);
  assert_eq!(upload_allowance(27_000_000,26_500_000,1_000_000),0);
 }
 #[test]fn audio_envelope_retains_endpoints_and_reversal(){
  assert_eq!(loudness_ramp(600,1500,0,80),600);
  assert_eq!(loudness_ramp(600,1500,40,80),1050);
  assert_eq!(loudness_ramp(1050,-1500,0,80),1050);
  assert_eq!(loudness_ramp(1050,-1500,80,80),-1500);
 }
 #[test]fn scene_depth_edges_are_continuous(){
  assert_eq!(edge_factor(0.,600.,90.,470.),1.);
  assert_eq!(edge_factor(300.,600.,90.,470.),0.);
  assert_eq!(edge_factor(600.,600.,90.,470.),1.);
  let mut last=0.;for y in 470..601{let next=edge_factor(y as f32,600.,90.,470.);assert!(next>=last);last=next;}
 }
}

/// Quarter-resolution ROI, expanded for both blur levels and bilinear filter footprints.
pub fn blur_regions(height:i32,density:f32,top:f32,bottom:f32,radius:f32)->i64{
 if height<=0||!density.is_finite()||density<=0.{return 0}
 let q=(height as i64+3)/4;
 let pad=radius.max(0.)*density*1.28+16.;
 let upper=((top*density+pad)/4.).ceil().max(0.)as i64;
 let lower=((height as f32-bottom*density+pad)/4.).ceil().max(0.)as i64;
 let(upper,lower)=if upper+lower>=q{(q,0)}else{(upper,lower)};
 (upper<<32)|(lower&0xffff_ffff)
}
/// Bound chord error conservatively; large covers retain the original 32x32 mesh.
pub fn mesh_level(span:f32,radius:f32,density:f32)->i32{
 if !span.is_finite()||!radius.is_finite()||!density.is_finite()||radius<=0.||density<=0.{return 2}
 let needed=(span*span*density/(radius*0.20)).sqrt();
 if needed<=8.{0}else if needed<=16.{1}else{2}
}
#[cfg(test)]mod workload_tests{
 use super::*;
 #[test]fn blur_roi_keeps_all_dependency_taps(){
  for height in [600,1200,2670,3200]{for density in [1.,2.,3.,4.]{
   let h=height as f32/density;let top=(h*0.18).clamp(56.,120.);let bottom=h-(h*0.23).clamp(72.,152.);
   let packed=blur_regions(height,density,top,bottom,14.);let upper=packed>>32;let lower=packed&0xffff_ffff;let q=(height as i64+3)/4;
   assert!(upper+lower<=q);
   if lower>0{assert!(upper as f32*4.>=top*density+14.*density*1.28+16.);assert!(lower as f32*4.>=height as f32-bottom*density+14.*density*1.28+16.);}
  }}
 }
 #[test]fn mesh_only_relaxes_subpixel_small_tiles(){
  for span in [20.,45.,100.,150.,300.,1500.]{
   let level=mesh_level(span,900.,3.);let n=[8.,16.,32.][level as usize];
   if level<2{assert!(span*span*3./(900.*n*n)<=0.20);}
  }
  assert_eq!(mesh_level(45.,900.,3.),0);assert_eq!(mesh_level(100.,900.,3.),1);assert_eq!(mesh_level(300.,900.,3.),2);
 }
}

/// Stable precision buckets. One enlarged album must not promote every other album's mesh.
/// Smooth spatial LOD for the bevel: zero work below 48dp, full detail from 92dp.
/// Derivative is zero at both endpoints, including when a gesture reverses midway.
pub fn card_highlight(short:f32)->f32{
 let t=((short-48.)/44.).clamp(0.,1.);t*t*(3.-2.*t)
}
/// bindings: high-resolution layer/ready, preview atlas slot/ready, cached ARGB color.
/// output: rect, artwork binding, preview binding, RGB + Rust-computed edge visibility.
pub fn pack_instances_tagged(frame:&[f32],bindings:&[i32],out:&mut[f32],density:f32)->Option<([usize;3],u64)>{
 let count=*frame.get(16)?;
 if !count.is_finite()||count<0.||count>384.||!density.is_finite()||density<=0.{return None}
 let count=count as usize;
 if frame.len()<32+count*16||bindings.len()<count*5||out.len()<count*16{return None}
 let radius=frame[5].min(frame[6]);let zoom=frame[4];
 if !zoom.is_finite()||zoom<=0.||!radius.is_finite()||radius<=0.{return None}
 let mut counts=[0usize;3];let mut levels=[0usize;384];
 for n in 0..count{
  let at=32+n*16;let span=frame[at+3].max(frame[at+4])*zoom;
  let level=mesh_level(span,radius,density)as usize;levels[n]=level;counts[level]+=1;
 }
 let mut cursor=[0,counts[0],counts[0]+counts[1]];let mut lifted=0u64;
 let action=super::daily_spatial::ACTION;
 let owner=if frame.get(action+27).copied().unwrap_or(0.)>0.5{frame[action+4]}else{-1.};
 for n in 0..count{
  let src=32+n*16;let b=n*5;let dst=cursor[levels[n]]*16;cursor[levels[n]]+=1;
  out[dst..dst+4].copy_from_slice(&frame[src+1..src+5]);
  out[dst+4]=bindings[b]as f32;out[dst+5]=if frame[src+7]==owner{1.}else{0.};
  if out[dst+5]>0.{lifted=(((dst/16+1)as u64)<<30)|((levels[n]as u64)<<40);}
  out[dst+6]=frame[src];out[dst+7]=bindings[b+1]as f32 + super::daily_spatial::lyrics_chip_flag(frame, frame[src+7]);
  let small=bindings[b+2];out[dst+8]=if small>=0{(small%32*64)as f32}else{0.};
  out[dst+9]=if small>=0{(small/32*64)as f32}else{0.};out[dst+10]=bindings[b+3]as f32;out[dst+11]=if frame[src+15]>0.5{frame[src+9]}else{-frame[src+9]};
  let rgb=bindings[b+4]as u32;
  out[dst+12]=((rgb>>16)&255)as f32/255.;out[dst+13]=((rgb>>8)&255)as f32/255.;out[dst+14]=(rgb&255)as f32/255.;
  let projected_short=frame[src+3].min(frame[src+4])*zoom*frame[src+8];
  out[dst+15]=card_highlight(projected_short);
 }
 Some((counts,lifted))
}
#[cfg(test)]
fn pack_instances(frame:&[f32],bindings:&[i32],out:&mut[f32],density:f32)->Option<[usize;3]>{pack_instances_tagged(frame,bindings,out,density).map(|v|v.0)}
#[cfg(test)]mod batch_tests{
 use super::*;
 #[test]fn enlargement_does_not_promote_neighbors_and_preserves_bindings(){
  let mut f=vec![0.;32+80*16];f[16]=80.;f[4]=1.;f[5]=900.;f[6]=1100.;
  let mut b=vec![0;80*5];let mut out=vec![0.;80*16];
  for i in 0..80{let s=32+i*16;f[s]=i as f32;f[s+1]=i as f32*110.;f[s+3]=100.;f[s+4]=100.;b[i*5]=i as i32+1;b[i*5+1]=1;b[i*5+2]=i as i32;b[i*5+3]=1;b[i*5+4]=0xFF2468ACu32 as i32;}
  assert_eq!(pack_instances(&f,&b,&mut out,3.),Some([0,80,0]));
  f[32+7*16+3]=300.;f[32+7*16+4]=300.;
  assert_eq!(pack_instances(&f,&b,&mut out,3.),Some([0,79,1]));
  assert_eq!(out[79*16+6],7.);assert_eq!(out[79*16+4],8.);assert_eq!(out[79*16+8],7.*64.);
  let mut ids:Vec<_>=out.chunks_exact(16).map(|v|v[6]as usize).collect();ids.sort_unstable();assert_eq!(ids,(0..80).collect::<Vec<_>>());
 }
 #[test]fn material_color_and_scale_fade_are_preserved(){
  let mut f=vec![0.;48];f[16]=1.;f[4]=1.;f[5]=900.;f[6]=1100.;f[35]=100.;f[36]=100.;f[40]=1.;
  let b=[1,1,0,1,0xFF2468ACu32 as i32];let mut out=[0.;16];
  assert!(pack_instances(&f,&b,&mut out,3.).is_some());
  assert_eq!(&out[12..15],&[36./255.,104./255.,172./255.]);assert_eq!(out[15],1.);
  f[4]=0.45;pack_instances(&f,&b,&mut out,3.);assert_eq!(out[15],0.);
  assert_eq!(card_highlight(48.),0.);assert_eq!(card_highlight(92.),1.);
  let mut last=0.;for n in 0..1000{let a=card_highlight(40.+n as f32*0.06);assert!(a>=last);last=a;}
  assert!(pack_instances(&f,&b[..4],&mut out,3.).is_none());
  assert!(pack_instances(&f,&b,&mut out[..12],3.).is_none());
 }
 #[test]fn invalid_buffers_are_rejected(){let mut out=[0.;12];assert_eq!(pack_instances(&[],&[],&mut out,3.),None);}
}

#[cfg(test)] mod lift_batch_tests {
 use super::*;
 #[test]fn foreground_owner_survives_lod_grouping_and_dislike(){
  let mut f=vec![0.;crate::daily_spatial::FLOATS];let a=crate::daily_spatial::ACTION;
  f[16]=3.;f[4]=1.;f[5]=900.;f[6]=1100.;f[a+27]=1.;f[a+4]=77.;
  for n in 0..3{let at=32+n*16;f[at+3]=if n==1{900.}else{80.};f[at+4]=100.;f[at+7]=76.+n as f32;f[at+8]=1.;f[at+9]=1.;}
  let bindings=vec![0;15];let mut out=vec![0.;48];let(counts,tag)=pack_instances_tagged(&f,&bindings,&mut out,3.).unwrap();
  assert_eq!(counts.iter().sum::<usize>(),3);let slot=(((tag>>30)&1023)-1)as usize;
  assert_eq!(out[slot*16+5],1.);assert_eq!(out[slot*16+2],900.);assert_eq!(out.chunks(16).filter(|v|v[5]>0.).count(),1);
  f[a+27]=0.;let(_,tag)=pack_instances_tagged(&f,&bindings,&mut out,3.).unwrap();assert_eq!(tag,0);assert!(out.chunks(16).all(|v|v[5]==0.));
 }
}

/// Cached context blur: 25 contiguous Gaussian taps represented by 13 bilinear samples.
/// Output is seven (texel offset, normalized weight) pairs: center plus six mirrored pairs.
/// The pyramid has already low-pass filtered each 2x reduction, never a sparse full-size gather.
pub fn context_blur_kernel(radius_dp:f32,density:f32,out:&mut[f32])->bool{
 if out.len()<14||!radius_dp.is_finite()||!density.is_finite()||radius_dp<=0.||density<=0.{return false}
 let sigma=(radius_dp*density/(3.*8.)).clamp(1.,5.);
 let mut weights=[0.;13];let mut sum=0.;
 for(i,w)in weights.iter_mut().enumerate(){*w=(-0.5*(i as f32/sigma).powi(2)).exp();sum+=*w*if i==0{1.}else{2.};}
 out[0]=0.;out[1]=weights[0]/sum;
 for pair in 0..6{let i=pair*2+1;let total=weights[i]+weights[i+1];out[2+pair*2]=i as f32+weights[i+1]/total;out[3+pair*2]=total/sum;}
 true
}
#[cfg(test)]mod context_blur_tests{
 use super::*;
 #[test]fn normalized_dense_kernel_has_no_shifted_lobes(){
  for density in [1.,2.,3.,4.]{let mut k=[0.;14];assert!(context_blur_kernel(28.,density,&mut k));
   let sum=k[1]+2.*(0..6).map(|i|k[3+i*2]).sum::<f32>();assert!((sum-1.).abs()<0.00001);
   // Reconstruct the real linear-filter taps: every texel is covered, monotonically fading.
   let mut taps=[0.;13];taps[0]=k[1];
   for pair in 0..6{let i=pair*2+1;let offset=k[2+pair*2];let weight=k[3+pair*2];assert!(offset>=i as f32&&offset<=(i+1)as f32);
    let fraction=offset-i as f32;taps[i]=weight*(1.-fraction);taps[i+1]=weight*fraction;}
   for i in 1..13{assert!(taps[i]>=0.&&taps[i]<=taps[i-1]+0.000001);}
  }
  assert!(!context_blur_kernel(f32::NAN,3.,&mut[0.;14]));assert!(!context_blur_kernel(28.,3.,&mut[0.;13]));
 }
}
