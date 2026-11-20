#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D u_scene;
uniform sampler2D u_depth;
uniform sampler2D u_soft;
uniform sampler2D u_far;
uniform sampler2D u_context_scene;
uniform float u_context_mix;
uniform float u_background_scale;
uniform vec4 u_scene_rect; // padded source offset.xy and visible span.zw
uniform vec4 u_lift_bounds;
uniform vec2 u_screen;
uniform float u_shadow;
uniform vec4 u_depth_policy; // Rust: top band dp, bottom start dp, far depth dp, background strength
uniform float u_height;
uniform float u_dark;
uniform float u_rhine;
uniform vec4 u_key_light;
uniform float u_output_mode; // 1 linear scRGB display; 2 SDR capture
in vec2 v_uv;
out vec4 color;
// RHINE_SHARED_LIGHT
vec3 transitionBackdrop(){
 if(u_dark>.5)return vec3(0.);
 if(u_rhine<.5)return vec3(1.);
 vec2 point=(v_uv-.5)/max(u_background_scale,.9)+.5;
 return rhineBackdropColor(vec2(point.x,1.-point.y)*u_screen,u_key_light);
}
void main(){
 // Sample the retained background with Rust's scale, without moving the world/camera.
 // Receding portrait views now sample the rendered guard band, not stretched edge texels.
 vec2 uv=u_scene_rect.xy+((v_uv-.5)/max(u_background_scale,.9)+.5)*u_scene_rect.zw;
 vec3 rgb=vec3(0.);
 // At full blur only one background lookup remains; skip sharp/depth/edge texture traffic.
 if(u_context_mix<.9999){
  float y=(1.0-v_uv.y)*u_height;
  float edge=0.0;
  if(u_depth_policy.w>0.0)edge=max(1.0-smoothstep(0.0,u_depth_policy.x,y),smoothstep(u_depth_policy.y,u_height,y));
  vec3 sharp=texture(u_scene,uv).rgb;
  rgb=sharp;
  if(edge>.0001){
   float distance=max(0.0,(texture(u_depth,uv).r-.5)*3600.0);
   float amount=edge*mix(.35,1.0,clamp(distance/u_depth_policy.z,0.0,1.0));
   rgb=mix(sharp,texture(u_soft,uv).rgb,min(1.0,amount*2.0));
   rgb=mix(rgb,texture(u_far,uv).rgb,max(0.0,amount*2.0-1.0));
   rgb=mix(rgb,transitionBackdrop(),edge*edge*u_depth_policy.w);
  }
 }
 // Prefiltered pyramid + contiguous Gaussian; cached while only the lift spring changes.
 if(u_context_mix>.0001){
 vec3 backdrop=texture(u_context_scene,uv).rgb;
 backdrop=mix(backdrop,transitionBackdrop(),mix(.12,.26,u_dark));
 rgb=mix(rgb,backdrop,u_context_mix);
 vec2 pixel=vec2(v_uv.x,1.-v_uv.y)*u_screen;
 vec2 center=(u_lift_bounds.xy+u_lift_bounds.zw)*.5+vec2(0.,9.);
 vec2 halfSize=(u_lift_bounds.zw-u_lift_bounds.xy)*.5;
 vec2 q=abs(pixel-center)-max(halfSize-vec2(16.),vec2(0.));
 float d=length(max(q,0.))+min(max(q.x,q.y),0.)-16.;
 float shadow=exp(-max(d,0.)*max(d,0.)/450.)*u_shadow*mix(.20,.42,u_dark);
 rgb*=1.-shadow;
 }
 if(u_output_mode>.5){
  vec3 linear=mix(rgb/12.92,pow(max((rgb+.055)/1.055,vec3(0.)),vec3(2.4)),step(vec3(.04045),rgb));
  if(u_output_mode<1.5){color=vec4(linear,1.);return;}
  float peak=max(linear.r,max(linear.g,linear.b));float mapped=peak<=.7?peak:.7+.3*(1.-exp(-(peak-.7)/.3));
  linear*=mapped/max(peak,.00001);
  rgb=mix(linear*12.92,1.055*pow(max(linear,vec3(0.)),vec3(1./2.4))-.055,step(vec3(.0031308),linear));
 }
 color=vec4(rgb,1.0);
}
