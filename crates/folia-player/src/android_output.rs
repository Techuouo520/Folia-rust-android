//! Native shared AAudio session. No EQ or legacy effects precede the headroom stage.
use std::{ffi::c_void,sync::{Arc,atomic::Ordering}};
use rtrb::{Consumer,Producer,RingBuffer};
use crate::{PlayerError,shared::{Shared,PlayerState},analyser::push_samples,bass::{self,Bass}};
unsafe extern "C" {
 fn folia_output_open(owner:*mut c_void,render:unsafe extern "C" fn(*mut c_void,*mut f32,i32,i32),error:unsafe extern "C" fn(*mut c_void,i32),session:*mut i32)->*mut c_void;
 fn folia_output_start(p:*mut c_void)->i32;
 fn folia_output_pause(p:*mut c_void)->i32;
 fn folia_output_maintain(p:*mut c_void);
 fn folia_output_close(p:*mut c_void);
}
struct Callback{consumer:Consumer<f32>,tap:Producer<f32>,shared:Arc<Shared>,bass:Bass,token:u64,session:i32,ramp:f32}
pub struct Stream{raw:*mut c_void,callback:Box<Callback>}
unsafe impl Send for Stream{} // close() joins native callbacks before dropping their context.
impl Stream{
 pub fn maintain(&self){unsafe{folia_output_maintain(self.raw)}}
 pub fn play(&self)->Result<(),PlayerError>{check(unsafe{folia_output_start(self.raw)})}
 pub fn pause(&self)->Result<(),PlayerError>{check(unsafe{folia_output_pause(self.raw)})}
}
fn check(code:i32)->Result<(),PlayerError>{if code>=0{Ok(())}else{Err(PlayerError::Output(format!("AAudio {code}")))}}
impl Drop for Stream{fn drop(&mut self){
 unsafe{folia_output_close(self.raw)};
 if bass::TOKEN.compare_exchange(self.callback.token,0,Ordering::AcqRel,Ordering::Relaxed).is_ok(){bass::READY.store(0,Ordering::Release);bass::SESSION.store(0,Ordering::Release);}
}}
pub struct Output{pub stream:Stream,pub rate:u32,pub channels:usize}
pub fn open(shared:Arc<Shared>,tap:Producer<f32>)->Result<(Output,Producer<f32>),PlayerError>{
 let(producer,consumer)=RingBuffer::new(48000*2*2);
 let token=bass::next_token();
 let dsp=Bass::new(48000,2,bass::HEADROOM).ok_or_else(||PlayerError::Output("bass initialization".into()))?;
 let mut callback=Box::new(Callback{consumer,tap,shared,bass:dsp,token,session:0,ramp:0.});let mut session=0;
 let raw=unsafe{folia_output_open((&mut *callback as *mut Callback).cast(),render,on_error,&mut session)};
 if raw.is_null(){return Err(PlayerError::Output("shared float session initialization".into()))}
 callback.session=session;
 bass::SESSION.store(session,Ordering::Release);bass::DEVICE.store(0,Ordering::Relaxed);bass::ERROR.store(0,Ordering::Release);bass::READY.store(0,Ordering::Release);bass::TOKEN.store(token,Ordering::Release);
 Ok((Output{stream:Stream{raw,callback},rate:48000,channels:2},producer))
}
unsafe extern "C" fn on_error(owner:*mut c_void,code:i32){
 bass::ERROR.store(code,Ordering::Release);bass::READY.store(0,Ordering::Release);
 // A disconnected AAudio stream may never invoke another data callback.
 let c=unsafe{&*owner.cast::<Callback>()};c.shared.set_state(PlayerState::Failed);
}
unsafe extern "C" fn render(owner:*mut c_void,data:*mut f32,frames:i32,device:i32){
 let c=unsafe{&mut *owner.cast::<Callback>()};if frames<=0{return}
 let out=unsafe{std::slice::from_raw_parts_mut(data,frames as usize*2)};out.fill(0.);bass::DEVICE.store(device,Ordering::Relaxed);
 if c.shared.take_flush(){let n=c.consumer.slots();if let Ok(chunk)=c.consumer.read_chunk(n){chunk.commit_all();}c.bass.reset();c.ramp=0.;}
 // API 26/27 retain dry playback; no unsupported -55 dB/DP contract is claimed there.
 if bass::ERROR.load(Ordering::Acquire)!=0{c.shared.set_state(PlayerState::Failed);return}
 let paired=c.session>0;
 if c.shared.state()!=PlayerState::Playing||(paired&&bass::READY.load(Ordering::Acquire)!=c.token){c.shared.starved.store(false,Ordering::Release);c.ramp=0.;return}
 let available=c.consumer.slots().min(out.len())/2*2;let done=c.shared.decoder_done.load(Ordering::Acquire);
 c.shared.starved.store(available<out.len()&&!done,Ordering::Release);
 if available<out.len()&&!done&&c.shared.started(){c.shared.add_underrun(((out.len()-available)/2)as u64);}
 if let Ok(chunk)=c.consumer.read_chunk(available){
  let(a,b)=chunk.as_slices();let gain=if paired{bass::HEADROOM}else{1.};
  for(dst,src)in out[..available].iter_mut().zip(a.iter().chain(b)){*dst=if src.is_finite(){*src*gain}else{0.};}
  chunk.commit_all();
 }
 if paired{c.bass.process(&mut out[..available],2,true);}
 let volume=c.shared.volume();let mut mono=[0.;256];let mut len=0;
 for frame in out[..available].chunks_exact_mut(2){c.ramp=(c.ramp+1./480.).min(1.);let v=volume*c.ramp;frame[0]*=v;frame[1]*=v;
  mono[len]=(frame[0]+frame[1])*0.5/if paired{bass::HEADROOM}else{1.};len+=1;if len==256{push_samples(&mut c.tap,&mono);len=0;}}
 if len>0{push_samples(&mut c.tap,&mono[..len]);}c.shared.add_played((available/2)as u64);
 if available==0&&done{c.shared.set_state(PlayerState::Ended);}
}
