#version 300 es
precision highp float;
precision highp sampler2D;
precision highp sampler2DArray;
uniform sampler2DArray u_covers;
uniform sampler2D u_previews;
uniform vec4 u_cover_blend[25];
uniform sampler2D u_scene;
uniform sampler2D u_depth;
uniform sampler2D u_soft;
uniform sampler2D u_far;
uniform sampler2D u_context;
uniform sampler2D u_frost_scene; // cached half-resolution dense Gaussian, never the write target
uniform vec2 u_screen;
uniform vec2 u_pixels;
uniform vec2 u_origin;
uniform float u_hdr_reach; // sqrt(live headroom), computed once per display event
uniform float u_hdr_headroom; // live target HDR peak / SDR white; 1 means SDR
#ifdef RHINE_RIM_PASS
const float u_output_mode=0.;
#else
uniform float u_output_mode;
#endif // 0 retained gamma-domain colour, 1 linear scRGB, 2 SDR capture
uniform vec4 u_policy;
uniform float u_dark;
#ifdef RHINE_RIM_PASS
const float u_surface=1.;
#else
uniform float u_surface;
#endif
uniform vec4 u_key_light; // Rust RHINE+8..11: finite source xyz and soft radius, in dp
#ifdef RHINE_RIM_PASS
const float u_material_mode=4.;
#else
uniform float u_material_mode;
#endif // 0 legacy glass, 1 matte, 2 tinted glass, 3 opaque, 4 glass rim
uniform vec2 u_material_geometry; // Rust RHINE+13..14: physical thickness, rim transmission
uniform float u_frost_radius; // Rust-defined screen dp, independent of density/album scale
#ifdef RHINE_RIM_PASS
const float u_foreground=0.;
#else
uniform float u_foreground;
#endif
uniform float u_blur;
uniform float u_background_scale;
uniform vec4 u_scene_rect;
uniform vec2 u_scene_texel;
in vec3 v_local;
in vec3 v_normal;
in vec3 v_position;
in vec3 v_local_normal;
flat in vec3 v_incident;
flat in vec4 v_art;
flat in vec4 v_info;
flat in vec4 v_material;
flat in float v_scale;
flat in vec4 v_emitter; // Rust-selected forward area light
flat in vec4 v_emitter_normal; // outward face normal and Rust emission envelope
flat in vec4 v_emitter_color;
flat in vec4 v_emitter2;
flat in vec4 v_emitter_normal2;
flat in vec4 v_emitter_color2;
out vec4 color;
// RHINE_SHARED_LIGHT
vec3 decodeSrgb(vec3 x){return mix(x/12.92,pow(max((x+.055)/1.055,vec3(0.)),vec3(2.4)),step(vec3(.04045),x));}
vec3 encodeLinear(vec3 x){return mix(x*12.92,1.055*pow(max(x,vec3(0.)),vec3(1./2.4))-.055,step(vec3(.0031308),x));}
vec4 finishColor(vec3 rgb,float alpha){
 if(u_output_mode>.5){vec3 linear=decodeSrgb(max(rgb,vec3(0.)));
  if(u_output_mode<1.5)return vec4(linear,alpha);
  // SDR captures compress only highlight headroom, retaining hue and dark detail.
  float peak=max(linear.r,max(linear.g,linear.b));float mapped=peak<=.7?peak:.7+.3*(1.-exp(-(peak-.7)/.3));
  rgb=encodeLinear(linear*(mapped/max(peak,.00001)));
 }return vec4(rgb,alpha);
}
flat in float v_lyrics;
vec2 coverDx,coverDy;
vec3 coverSample(vec2 uv){
 // HD uses the projected footprint; the independent preview atlas stays at
 // level zero so adjacent album tiles never bleed into one another.
 float blend=0.;
 if(v_art.y>.5){
  int layer=clamp(int(v_art.x),0,96);
  blend=smoothstep(0.,1.,u_cover_blend[layer/4][layer%4]);
  if(blend>=1.)return textureGrad(u_covers,vec3(uv,v_art.x),coverDx,coverDy).rgb;
 }
 vec3 preview=mix(vec3(.90),vec3(.12),u_dark);
 if(v_info.x>.5)preview=textureLod(u_previews,(v_art.zw+vec2(.5)+uv*63.)/vec2(textureSize(u_previews,0)),0.).rgb;
 if(blend<=0.)return preview;
 vec3 high=textureGrad(u_covers,vec3(uv,v_art.x),coverDx,coverDy).rgb;
 return mix(preview,high,blend);
}
// COVER_LYRICS_CHIP
vec3 album(vec2 uv){
 uv=clamp(uv,0.,1.);if(v_incident.z>0.)uv.x=1.-uv.x;
 vec3 art=coverSample(uv);
 return v_lyrics>.5?printLyricsChip(uv,art,v_scale):art;
}
// Reduce only the refractive displacement when the source footprint is exhausted. Repeating
// border texels would turn the silhouette into a long stripe; the base scene remains spatial.
vec2 boundedRefraction(vec2 requested,vec2 base){
 vec2 margin=.5*u_scene_texel;vec2 delta=requested-base;
 vec2 room=mix(base-margin,vec2(1.)-margin-base,step(vec2(0.),delta));
 vec2 ratio=max(room,vec2(0.))/max(abs(delta),vec2(.000001));
 return base+delta*clamp(min(ratio.x,ratio.y),0.,1.);
}
vec3 backdropTint(vec2 sourceUv){
 if(u_dark>.5)return vec3(0.);
 vec2 point=(sourceUv-u_scene_rect.xy)/u_scene_rect.zw;
 return rhineBackdropColor(vec2(point.x,1.-point.y)*u_screen,u_key_light);
}
vec3 backdrop(vec2 uv){
 vec2 pixelUv=(gl_FragCoord.xy+u_origin)/u_pixels;
 if(u_foreground<.5)return texture(u_scene,boundedRefraction(uv,pixelUv)).rgb;
 vec2 sampleUv=u_scene_rect.xy+((uv-.5)/max(u_background_scale,.9)+.5)*u_scene_rect.zw;
 vec2 baseUv=u_scene_rect.xy+((pixelUv-.5)/max(u_background_scale,.9)+.5)*u_scene_rect.zw;
 sampleUv=boundedRefraction(sampleUv,baseUv);
 if(u_blur>.9999)return mix(texture(u_context,sampleUv).rgb,backdropTint(sampleUv),mix(.12,.26,u_dark));
 float y=(1.-uv.y)*u_screen.y;float edge=0.;
 if(u_policy.w>0.)edge=max(1.-smoothstep(0.,u_policy.x,y),smoothstep(u_policy.y,u_screen.y,y));
 vec3 rgb=texture(u_scene,sampleUv).rgb;
 if(edge>.0001){float depth=max(0.,(texture(u_depth,sampleUv).r-.5)*8000.);float amount=edge*mix(.35,1.,clamp(depth/max(u_policy.z,1.),0.,1.));rgb=mix(rgb,texture(u_soft,sampleUv).rgb,min(1.,amount*2.));rgb=mix(rgb,texture(u_far,sampleUv).rgb,max(0.,amount*2.-1.));rgb=mix(rgb,backdropTint(sampleUv),edge*edge*u_policy.w);}
 if(u_blur>.0001)rgb=mix(rgb,mix(texture(u_context,sampleUv).rgb,backdropTint(sampleUv),mix(.12,.26,u_dark)),u_blur);
 return rgb;
}
// Filtered background only. The exterior print uses its own unfiltered mesh/pass.
vec3 frostedBackdrop(vec2 uv){
 vec2 pixelUv=(gl_FragCoord.xy+u_origin)/u_pixels;
 if(u_foreground<.5)return texture(u_frost_scene,boundedRefraction(uv,pixelUv)).rgb;
 vec2 at=u_scene_rect.xy+((uv-.5)/max(u_background_scale,.9)+.5)*u_scene_rect.zw;
 vec2 base=u_scene_rect.xy+((pixelUv-.5)/max(u_background_scale,.9)+.5)*u_scene_rect.zw;
 at=boundedRefraction(at,base);vec3 rgb=texture(u_frost_scene,at).rgb;
 float y=(1.-uv.y)*u_screen.y;float edge=0.;
 if(u_policy.w>0.)edge=max(1.-smoothstep(0.,u_policy.x,y),smoothstep(u_policy.y,u_screen.y,y));
 rgb=mix(rgb,backdropTint(at),edge*edge*u_policy.w);
 if(u_blur>.0001)rgb=mix(rgb,mix(texture(u_context,at).rgb,backdropTint(at),mix(.12,.26,u_dark)),u_blur);
 return rgb;
}
// A nearby finite emitter. Position, distance falloff and surface orientation all
// contribute, including across a single top face. No camera-pad or capture-origin shift.
vec3 archiveLighting(vec3 pigment,vec3 normal,bool print){
 if(u_dark>.5)return pigment*sqrt(print?.18:.055);
 return rhineDiffuse(pigment,normal,v_position,u_key_light,print);
}
// Front-side printed rectangle: 0.03-unit left inset, 0.045 x 0.09 units.
// Its top meets the sleeve top; it stays entirely outside the opaque album print.
float archiveAccent(vec3 n){
 if(n.z<.92)return 0.;
 vec2 point=vec2(.6+v_local.x,.6-v_local.y);
 vec2 aa=max(fwidth(point),vec2(.00001));
 float left=smoothstep(.03-aa.x*.5,.03+aa.x*.5,point.x);
 vec2 rightBottom=vec2(1.)-smoothstep(-aa*.5,aa*.5,point-vec2(.075,.09));
 return left*rightBottom.x*rightBottom.y;
}
// A fixed pigment correction, independent of distance or incident-light thresholds.
vec3 archiveAccentLighting(vec3 normal){
 float luminance=dot(v_material.rgb,vec3(.2126,.7152,.0722));
 vec3 pigment=mix(vec3(luminance),v_material.rgb,.78);
 pigment=clamp((pigment-.5)*.90+.5,0.,1.);
 return archiveLighting(pigment,normal,false);
}
// Dielectric reflection from the same finite warm emitter as the solid's light.
// GGX uses the emitter's angular size, not just its distance attenuation. Physical
// bevel normals sweep the highlight as the card moves; there is no painted border.
vec3 archiveGlassReflection(vec3 normal,float frost,out float fresnel,out vec3 warmSpecular){
 warmSpecular=vec3(0.);
 vec3 view=vec3(0.,0.,-1.);float nv=max(dot(normal,view),.001);
 float f0=pow((1.49-1.)/(1.49+1.),2.);
 fresnel=f0+(1.-f0)*pow(1.-clamp(nv,0.,1.),5.);
 if(u_dark>.5)return vec3(0.);
 vec3 delta=u_key_light.xyz-v_position;float distance2=max(dot(delta,delta),1.);
 float radius=max(u_key_light.w,1.);
 // Closest point on the finite emitter to the actual reflection ray. A light
 // centre-only half vector cannot form the correct highlight on moving bevels.
 vec3 ray=reflect(-view,normal);
 vec3 toRay=ray*max(dot(delta,ray),0.)-delta;
 vec3 representative=delta+toRay*min(radius/max(length(toRay),.0001),1.);
 vec3 light=normalize(representative);float nl=max(dot(normal,light),0.);
 vec3 halfway=normalize(view+light+vec3(0.,0.,-.00001));
 float nh=max(dot(normal,halfway),0.);float vh=max(dot(view,halfway),0.);
 float roughness=mix(.18,.32,frost);float alpha=roughness*roughness;
 float a2=alpha*alpha;
 float denom=nh*nh*(a2-1.)+1.;float distribution=a2/(3.14159265*denom*denom);
 // Normalize the broadened spherical lobe once. The old area*.32 roughness
 // inflation spread away its peak before applying the light attenuation again.
 float sphereAlpha=min(1.,alpha+radius/(2.*sqrt(distance2)));
 distribution*=a2/(sphereAlpha*sphereAlpha);
 float visibility=.5/max(nl*sqrt(nv*nv*(1.-a2)+a2)+nv*sqrt(nl*nl*(1.-a2)+a2),.00001);
 float halfF=f0+(1.-f0)*pow(1.-vh,5.);
 float direct=distribution*visibility*halfF*nl;
 vec3 reflected=reflect(-view,normal);
 // Broad studio fill integrates over a rough reflection direction. The finite
 // warm key remains a separate directional lobe; the fill is neutral and dim.
 float sky=smoothstep(-.65,.8,-reflected.y);
 vec3 environment=mix(vec3(.06,.08,.10),vec3(.52,.57,.60),sky);
 warmSpecular=ARCHIVE_WARM*direct*(ARCHIVE_KEY_RADIANCE*rhineKeyFalloff(v_position,u_key_light));
 return environment*fresnel+warmSpecular;
}
vec3 areaGlassLight(vec3 normal,float frost,float fresnel,vec4 emitter,vec4 emitterNormal,vec3 emitterColor){
 if(u_dark<.5)return vec3(0.);
 vec3 radiance=vec3(0.);
 if(emitterNormal.w>.0001&&emitter.w>.001){
  vec3 delta=emitter.xyz-v_position;float d2=max(dot(delta,delta),.001);
  float distance=sqrt(d2),radius=emitter.w;vec3 light=delta/distance;
  float reach=1.-smoothstep(radius*3.*u_hdr_reach,radius*5.*u_hdr_reach,distance);
  float area=radius*radius/(d2+radius*radius);
  // Integrate the nearby emitter's angular extent instead of treating the whole
  // cover as a single centre point (which made grazing glass almost unlit).
  float facing=dot(emitterNormal.xyz,-light);
  // Finite cover light, opening out like a soft flashlight rather than parallel
  // rays: the footprint grows with forward distance; the 40..70 degree edge fades.
  float axial=max(distance*facing,0.);
  float lateral=distance*sqrt(max(1.-facing*facing,0.));
  float inner=radius+axial*.84,outer=radius+axial*2.75;
  float coneT=clamp((lateral-inner)/max(outer-inner,.001),0.,1.);
  float beam=1.-coneT*coneT*(3.-2.*coneT);
  float outgoing=clamp((facing+.45*sqrt(area))/(1.+.45*sqrt(area)),0.,1.)*smoothstep(0.,.12,facing);
  // Exact zero contribution, not a quality threshold. Skip GGX/sqrt/pow only
  // outside the original finite reach/cone; active light arithmetic is unchanged.
  if(reach==0.||outgoing==0.||beam==0.)return vec3(0.);
  vec3 view=vec3(0.,0.,-1.);float nv=max(dot(normal,view),.001);
  float nl=max(dot(normal,light),0.);vec3 halfVector=normalize(light+view+vec3(0.,0.,-.00001));
  float nh=max(dot(normal,halfVector),0.),vh=max(dot(view,halfVector),0.);
  float a2=clamp(mix(.018,.055,frost)+area*.40,.018,.55);
  float denom=nh*nh*(a2-1.)+1.;float distribution=a2/(3.14159265*denom*denom);
  float gv=2.*nv/(nv+sqrt(a2+(1.-a2)*nv*nv));
  float gl=2.*nl/(nl+sqrt(a2+(1.-a2)*nl*nl));
  float f=.0387+.9613*pow(1.-vh,5.);
  float specular=distribution*gv*gl*f/(4.*nv+.0001);
  // Light entering the reverse face is visible through the glass, including
  // rough thin faces. Its transmission uses the shared material coefficient.
  float through=max(-dot(normal,light),0.)*u_material_geometry.y*(1.-fresnel);
  float scatter=abs(dot(normal,light))*frost*.22;
  vec3 tint=mix(emitterColor*emitterColor,vec3(1.),.12);
  radiance=tint*(4.5*u_hdr_headroom*emitterNormal.w*area*outgoing*reach*beam)*(specular+through+scatter);
 }
 return radiance;
}
// Two bounded area lights share the existing glass pass. Occlusion and source
// selection are cached in Rust; the combined result uses the existing HDR shoulder.
float glassVisibility(float packed,vec2 uv){
 if(packed==2340.)return 1.;if(packed==0.)return 0.;
 uint bits=uint(packed);vec4 v=vec4(bits&7u,(bits>>3)&7u,(bits>>6)&7u,(bits>>9)&7u)*.25;
 return mix(mix(v.x,v.y,uv.x),mix(v.z,v.w,uv.x),uv.y);
}
vec3 coverGlassLight(vec3 normal,float frost,float fresnel){
 // Match Rust's actual receiving corners. A visible side or top must not
 // flood the opposite, occluded part of the card with the same scalar light.
 vec2 uv=clamp((v_local.xy-vec2(-.575,-.70833335))/vec2(1.15,1.28333335),0.,1.);
 float shadow=glassVisibility(v_emitter_color.w,uv);
 float shadow2=glassVisibility(v_emitter_color2.w,uv);
 return areaGlassLight(normal,frost,fresnel,v_emitter,v_emitter_normal,v_emitter_color.rgb)*shadow
       +areaGlassLight(normal,frost,fresnel,v_emitter2,v_emitter_normal2,v_emitter_color2.rgb)*shadow2;
}
vec3 glowingCover(vec3 artwork,vec3 normal){
 if(u_dark<.5)return archiveLighting(artwork,normal,true);
 // Only the original bright detail uses HDR; black and midtones stay unchanged.
 vec3 original=decodeSrgb(artwork);float emission=clamp(v_material.w,0.,1.);
 float peak=max(original.r,max(original.g,original.b));
 // All channels share one gain. Original white can reach the live display peak,
 // without turning the whole photograph into a white plate or clipping channels.
 float highlight=smoothstep(.55,1.,peak);highlight*=highlight;
 float extension=max(0.,u_hdr_headroom-.94)*smoothstep(1.,1.1,u_hdr_headroom);
 float gain=mix(.18,.94,emission)+extension*emission*highlight;
 return encodeLinear(original*gain);
}
vec3 tintedGlassTransmission(vec3 background,float path,float frost,vec3 normal){
 // Beer-Lambert absorption, calibrated per physical slab thickness. Colour is
 // in the volume, not Lambert-painted onto the surface. Long grazing paths
 // absorb more light; the existing dense Gaussian supplies the frosted detail.
 vec3 tint=vec3(.74,.80,.83);
 float extraPath=max(path/max(u_material_geometry.x,.0001)-1.,0.);
 // Rust's coefficient is the normal-incidence transmission ceiling (2%).
 // Additional travel absorbs more colour; no light-theme bypass of this value.
 vec3 transmission=vec3(clamp(u_material_geometry.y,0.,1.))*exp(log(tint)*extraPath);
 vec3 through=decodeSrgb(background)*transmission;
 // Dense coloured glass retains a lit scattering body when transmission is low.
 // Broad wrapped incidence describes volume scattering; surface reflection is
 // still the separate dielectric GGX lobe, never painted onto the cover.
 vec3 direction=normalize(u_key_light.xyz-v_position);
 float wrap=mix(.12,.25,frost);
 float incidence=clamp((dot(normal,direction)+wrap)/(1.+wrap),0.,1.);
 float field=clamp(rhineKeyFalloff(v_position,u_key_light)/.06,0.,1.1);
 // Less ambient fill and angular wrap reveal face/bevel depth; preserve the
 // directly lit level instead of increasing whole-scene exposure.
 vec3 irradiance=vec3(.20)+ARCHIVE_WARM*(.86*field*incidence);
 return through+(vec3(1.)-transmission)*vec3(.68,.72,.74)*irradiance;
}
vec3 daylightGlass(vec3 body,vec3 reflection,vec3 warmSpecular,float fresnel){
 vec3 base=max(body*(1.-fresnel)+reflection-warmSpecular,vec3(0.));
 float basePeak=max(base.r,max(base.g,base.b));
 base/=max(1.,basePeak);basePeak=min(basePeak,1.);
 float energy=max(warmSpecular.r,max(warmSpecular.g,warmSpecular.b));
 // One smooth, hue-preserving highlight shoulder. Do not clamp to white and
 // then add a second HDR boost to an already clipped highlight.
 float budget=max(u_hdr_headroom-basePeak,0.);
 return encodeLinear(base+warmSpecular*(budget/(1.+energy)));
}
vec3 archiveSurface(vec3 normal,vec3 localNormal,vec3 artwork){
 vec3 pigment=mix(vec3(.82,.85,.86),vec3(.17,.20,.21),u_dark);
 bool art=abs(localNormal.z)>.92&&abs(v_local.x)<=.5&&abs(v_local.y)<=.5;
 vec3 lit=archiveLighting(art?artwork:pigment,normal,art);
 float accent=archiveAccent(localNormal)*step(.5,v_info.x);
 if(accent>.0001)lit=mix(lit,archiveAccentLighting(normal),accent);
 return lit;
}
vec3 archiveSurface(vec3 normal,vec3 localNormal){
 // Spine/edge pigment ignores artwork. Do not fetch a 768px cover for pixels
 // whose existing material predicate cannot consume it.
 vec3 art=vec3(0.);
 if(abs(localNormal.z)>.92&&abs(v_local.x)<=.5&&abs(v_local.y)<=.5)
  art=album(vec2(v_local.x+.5,.5-v_local.y));
 return archiveSurface(normal,localNormal,art);
}
void main(){
#ifndef RHINE_DEPTH_PASS
 // Compute before divergent face/material branches, including the opaque pass.
 vec2 artUv=vec2(v_local.x,-v_local.y);
 coverDx=dFdx(artUv);coverDy=dFdy(artUv);
#endif
#ifdef RHINE_DEPTH_PASS
 // Colour is masked off. Linking strips all optical varyings and calculations;
 // gl_Position uses the very same vertex transform as the subsequent colour pass.
 color=vec4(0.);
#elif defined(RHINE_OPAQUE_PASS)
 // Source core/print are already filtered for hidden instances by Rust. This
 // dedicated program has no discard or software depth lookup: opaque surfaces
 // can use hardware early depth rejection, with the exact same colour functions.
 vec3 normal=normalize(v_normal);
 vec3 rgb=u_surface>1.5?glowingCover(album(vec2(v_local.x+.5,.5-v_local.y)),normal)
                         :archiveSurface(normal,normalize(v_local_normal));
 // Exterior-glass mode 2 preserves its original unlit exterior photograph.
 if(u_surface>1.5&&u_material_mode<2.5)rgb=album(vec2(v_local.x+.5,.5-v_local.y));
 color=vec4(rgb,v_info.y);
#else
 #ifndef RHINE_RIM_PASS
 if(v_info.y<=.001)discard;
 // Foreground is a scheduling layer, not permission to ignore the scene's real depth.
 // Cover and glass use the same comparison throughout lift/return and cropped dust capture.
 if(u_foreground>.5&&u_material_mode>1.5){
  vec2 pixelUv=(gl_FragCoord.xy+u_origin)/u_pixels;
  vec2 depthUv=u_scene_rect.xy+((pixelUv-.5)/max(u_background_scale,.9)+.5)*u_scene_rect.zw;
  if(gl_FragCoord.z>texture(u_depth,depthUv).r+.000002)discard;
 }
 #endif
 if(u_surface>1.5){vec3 print=album(vec2(v_local.x+.5,.5-v_local.y));
  if(u_material_mode>2.5)print=glowingCover(print,normalize(v_normal));
  color=finishColor(print,v_info.y);return;}
 vec3 normal=normalize(v_normal),localNormal=normalize(v_local_normal);
 if(u_material_mode>2.5&&u_material_mode<3.5){color=finishColor(archiveSurface(normal,localNormal,vec3(0.)),v_info.y);return;}
 if(u_material_mode>.5&&u_material_mode<1.5){color=finishColor(archiveSurface(normal,localNormal),v_info.y);return;}
 if(u_surface<.5&&u_material_mode>3.5){color=finishColor(archiveSurface(normal,localNormal),v_info.y);return;}
 if(u_surface<.5){vec3 rgb=abs(localNormal.z)>.5?album(vec2(v_local.x+.5,.5-v_local.y)):v_material.rgb*.35;color=finishColor(rgb,v_info.y);return;}
 // The exterior photograph owns these pixels; no blur/refraction/coating over the print.
 #ifndef RHINE_RIM_PASS
 if(u_material_mode>1.5&&abs(localNormal.z)>.92&&abs(v_local.x)<=.5&&abs(v_local.y)<=.5)discard;
 #endif
 // Background rim mesh 6 already excludes the print opening and Rust excludes
 // hidden instances. This program contains no discard/manual depth, allowing
 // hardware depth rejection before the expensive optical/area-light shading.
 // Local normals distinguish frosted thin faces from the lightly milky front/back.
 // Blend the finish across the real bevel; never draw an outline or frost the large faces.
 float frost=1.-smoothstep(.35,.92,abs(localNormal.z));
 // Closed geometric bevels supply their own changing normals; no boundary stroke is painted.
 vec3 localRay=refract(normalize(v_incident),localNormal,1./1.49);
 float t=abs(localRay.z)>.001?-v_local.z/localRay.z:-1.;vec3 insert=v_local+localRay*t;
 bool throughInsert=t>=0.&&abs(insert.x)<=.5&&abs(insert.y)<=.5;
 vec3 ray=refract(vec3(0.,0.,1.),normal,1./1.49);
 // Keep the optical thickness equal to Rust rhine_mesh::THICK.
 float path=u_material_geometry.x/max(abs(ray.z),.12);
 vec2 uv=(gl_FragCoord.xy+u_origin)/u_pixels;
 vec2 offset=vec2(ray.x,-ray.y)*path*v_scale/u_screen;
 if(u_foreground<.5&&texture(u_depth,boundedRefraction(uv+offset,uv)).r<gl_FragCoord.z-.0001)offset=vec2(0.);
 vec3 transmitted=vec3(0.);if(u_material_mode<1.5)transmitted=throughInsert?album(vec2(insert.x+.5,.5-insert.y)):backdrop(uv+offset);
 if(u_material_mode>1.5){
  // Continuous separable Gaussian is cached by the renderer. One lookup, no sparse lobes.
  vec3 scattered=frostedBackdrop(uv+offset);
  // Neither the central solid nor the printed tag transmits. The solid/print
  // returned above; only the surrounding glass reaches this branch.
  float accent=archiveAccent(localNormal)*step(.5,v_info.x);
  float transmission=(u_material_mode>3.5?u_material_geometry.y:.12)*(1.-accent);
  vec3 rgb=mix(archiveSurface(normal,localNormal,vec3(0.)),scattered,transmission);
  if(u_material_mode>3.5){
   float fresnel;vec3 warmSpecular;vec3 reflection=archiveGlassReflection(normal,frost,fresnel,warmSpecular);
   if(u_dark<.5){
    vec3 glass=daylightGlass(tintedGlassTransmission(scattered,path,frost,normal),reflection,warmSpecular,fresnel);
    if(accent>.0001)glass=mix(glass,archiveAccentLighting(normal),accent);
    color=finishColor(glass,v_info.y);return;
   }
   // Reflective surface energy is separate from the bulk-transmission mix.
   // Composite in linear light; the opaque printed tab gets no glass coating.
   vec3 body=rgb*rgb;
   vec3 coverLight=coverGlassLight(normal,frost,fresnel);
   vec3 optical=body*(1.-fresnel)+reflection+coverLight;
   float shoulder=mix(.20,1.,u_dark);
   vec3 mapped=max(optical,vec3(0.))/(vec3(1.)+max(optical,vec3(0.))*shoulder);
   vec3 glass=sqrt(min(mapped,vec3(1.)));
   if(u_hdr_headroom>1.){
    // Keep the existing SDR body/fill. Spend HDR headroom only on the actual
    // finite warm reflection (light mode) or coloured sign light (dark mode).
    vec3 linear=decodeSrgb(glass);
    vec3 incident=u_dark>.5?coverLight:warmSpecular;
    float energy=max(incident.r,max(incident.g,incident.b));
    float highlight=smoothstep(.02,1.,energy);
    float basePeak=max(linear.r,max(linear.g,linear.b));
    float lift=max(0.,u_hdr_headroom-basePeak)*smoothstep(1.,1.1,u_hdr_headroom)*highlight;
    // Add the original light colour, not white. The available peak budget keeps
    // every channel within current headroom without RGB clipping or extra passes.
    linear+=incident*(lift/max(energy,.00001));
    glass=encodeLinear(linear);
   }
   rgb=glass;if(accent>.0001)rgb=mix(glass,archiveAccentLighting(normal),accent);
  }else{
   float softSpec=pow(max(dot(normal,normalize(vec3(-.24,-.42,-.88))),0.),6.);
   rgb+=vec3(.020,.023,.025)*softSpec*v_info.z;
  }
  color=finishColor(rgb,v_info.y);return;
 }

 if(frost>.001){
  // A stable, finite scattering footprint. Extra samples are confined to resolvable thin faces;
  // no per-frame noise, extra render target or Java/Rust animation work is introduced.
  if(v_info.z>.001){
   float spread=clamp(v_scale*.018,1.25,4.0);
   vec2 spreadUv=vec2(spread)/u_screen,artUv=vec2(insert.x+.5,.5-insert.y);
   float artSpread=spread/max(v_scale,1.);
   vec3 scattered=transmitted*.4;
   if(throughInsert){
    scattered+=(album(artUv+vec2(artSpread,artSpread))+album(artUv-vec2(artSpread,artSpread))
              +album(artUv+vec2(artSpread,-artSpread))+album(artUv+vec2(-artSpread,artSpread)))*.15;
   }else{
    vec2 at=uv+offset;
    scattered+=(backdrop(at+spreadUv)+backdrop(at-spreadUv)
              +backdrop(at+vec2(spreadUv.x,-spreadUv.y))+backdrop(at+vec2(-spreadUv.x,spreadUv.y)))*.15;
   }
   transmitted=mix(transmitted,scattered,frost*v_info.z);
  }
  // Bulk scattering softens the remaining contrast while retaining a transmitted album tint.
  vec3 diffuseTint=mix(vec3(.86,.89,.91),v_material.rgb,.08);
  transmitted=mix(transmitted,diffuseTint,frost*.30);
 }
 // White bulk scattering is a material property in both themes, not a white outline.
 // Keep artwork legible through the insert and a stronger milk tint in the clear margins.
 float milk=throughInsert?.075:.20;
 transmitted=mix(transmitted,vec3(.92,.94,.95),milk);
 float facing=clamp(dot(normal,vec3(0.,0.,-1.)),0.,1.);float f=1.-facing;float fresnel=.0387+.9613*f*f*f*f*f;
 vec3 reflected=reflect(vec3(0.,0.,1.),normal);
 vec3 environment=mix(vec3(.035,.06,.075),vec3(.50,.60,.63),smoothstep(-.6,.95,-reflected.y));
 if(v_info.z>.001&&frost<.999){
  float strip=exp(-pow((reflected.x+.32)/.085,2.))*smoothstep(-.7,.45,-reflected.y);
  float second=exp(-pow((reflected.x-.68)/.035,2.))*.4;
  environment+=vec3(.65,.70,.66)*(strip+second)*v_info.z*(1.-frost)*(1.-frost);
 }
 // Rough side faces integrate broad illumination instead of reflecting sharp studio strips.
 environment=mix(environment,vec3(.19,.24,.25),frost*.78);
 vec3 absorption=exp(-vec3(.11,.035,.06)*path);
 vec3 rgb=transmitted*absorption*(1.-fresnel)+environment*fresnel;
 if(v_info.z>.001){vec3 halfVector=normalize(normalize(vec3(-.35,-.60,-.72))+vec3(0.,0.,-1.));float spec=pow(max(dot(normal,halfVector),0.),mix(80.,8.,frost));rgb+=vec3(.72,.79,.76)*spec*v_info.z*mix(.38,.055,frost);}
 color=finishColor(rgb,v_info.y);
#endif
}
