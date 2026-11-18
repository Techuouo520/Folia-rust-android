#version 300 es
precision highp float;
precision highp sampler2DArray;
uniform sampler2DArray u_covers;
uniform sampler2D u_previews;
uniform vec4 u_cover_blend[25];
uniform float u_mode;
uniform float u_dark;
uniform float u_linear_output;
uniform vec3 u_accent;
uniform float u_lift_radius;
in vec2 v_uv;
in vec2 v_size;
in vec2 v_angles;
flat in vec4 v_info;
flat in vec4 v_preview;
flat in vec4 v_material;
in vec3 v_normal;
in float v_shade;
out vec4 color;
uniform float u_zoom;
flat in float v_lyrics;
vec3 coverSample(vec2 uv){
 float blend=0.;
 if(v_info.w>.5){
  int layer=clamp(int(v_info.x),0,96);
  blend=smoothstep(0.,1.,u_cover_blend[layer/4][layer%4]);
  if(blend>=1.)return textureLod(u_covers,vec3(uv,v_info.x),0.).rgb;
 }
 vec3 preview=vec3(mix(.89,.12,u_dark));
 if(v_preview.z>.5)preview=textureLod(u_previews,(v_preview.xy+vec2(.5)+uv*63.)/vec2(textureSize(u_previews,0)),0.).rgb;
 if(blend<=0.)return preview;
 vec3 high=textureLod(u_covers,vec3(uv,v_info.x),0.).rgb;
 return mix(preview,high,blend);
}
// COVER_LYRICS_CHIP
void main(){
 if(abs(v_preview.w)<.001)discard;
 if(v_info.y<.5&&u_mode>.01&&(abs(v_angles.x)>1.22||abs(v_angles.y)>1.22))discard;
 float radius=v_info.y>.5?u_lift_radius:mix(12.0,6.0,u_mode);
 vec2 q=abs((v_uv-.5)*v_size)-(v_size*.5-vec2(radius));
 float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
 float aa=max(fwidth(d),.3),alpha=1.0-smoothstep(-aa,aa,d);
 if(alpha<.001)discard;
 vec2 local=v_uv*v_size;float side=min(v_size.x,v_size.y);
 bool artwork=local.x<=side&&local.y<=side;
 // The extension is a single extracted album color. It never samples artwork UVs.
 vec3 rgb=mix(vec3(mix(.96,.028,u_dark)),v_material.rgb,mix(.20,.32,u_dark));
 if(artwork){
  vec2 uv=local/side;
  rgb=coverSample(uv);
  if(v_lyrics>.5)rgb=printLyricsChip(uv,rgb,side*mix(1.,u_zoom,u_mode));
 }
 // Reuse vertex curvature lighting instead of recomputing trigonometry per fragment.
 rgb*=mix(1.0,v_shade,u_mode);
 // Caption readability on compact rectangles, without resampling text with the artwork.
 if(v_preview.w>0.0)rgb*=1.0-.48*smoothstep(.60,1.0,v_uv.y)*u_mode;
 // Thin directional bevel on the real rounded boundary, not a uniform bright outline.
 // Rust fades detail by projected size; at zero, skip all bevel-normal/specular work.
 if(v_material.a>0.0&&u_mode>.01&&d> -2.5){
  vec2 signEdge=sign((v_uv-.5)*v_size);
  vec2 corner=max(q,0.0);
  vec2 edge=dot(corner,corner)>.0001?normalize(corner)*signEdge:
   (q.x>q.y?vec2(signEdge.x,0.0):vec2(0.0,signEdge.y));
  vec3 bevel=normalize(v_normal*.72+vec3(edge*.70,0.0));
  float spec=max(0.0,dot(bevel,vec3(-.325,-.557,.764)));
  spec*=spec;spec*=spec;spec*=spec;
  float rim=1.0-smoothstep(.25,1.6,abs(d+.70));
  float detail=v_material.a*u_mode*rim;
  float shadow=max(0.0,dot(edge,vec2(.50,.866)))*.055;
  rgb*=1.0-shadow*detail;
  rgb=mix(rgb,vec3(1.0),spec*detail*mix(.09,.19,u_dark));
 }
 if(u_linear_output>.5)rgb=mix(rgb/12.92,pow(max((rgb+.055)/1.055,vec3(0.)),vec3(2.4)),step(vec3(.04045),rgb));
 color=vec4(rgb,alpha*abs(v_preview.w));
}
