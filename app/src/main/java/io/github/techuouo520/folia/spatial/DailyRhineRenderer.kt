package io.github.techuouo520.folia.spatial

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


internal class DailyRhineRenderer @Throws(Exception::class)
constructor(context:Context?, private val hdrSurface:Boolean) {
    private val program:Int
    private val rimProgram:Int
    private val opaqueProgram:Int
    private val depthProgram:Int
    private val filterProgram:Int
    private val downProgram:Int
    private val filterVao:Int
    private val filterImage:Int
    private val filterStep:Int
    private val filterKernel:Int
    private val downImage:Int
    private val downTexel:Int
    private val frostTextures:IntArray = IntArray(2)
    private val frostFbos:IntArray = IntArray(2)
    private val kernelBytes:ByteBuffer? = ByteBuffer.allocateDirect(56).order(ByteOrder.nativeOrder())
    private val kernel:FloatBuffer? = kernelBytes!!.asFloatBuffer()
    private var frostWidth:Int = 0
    private var frostHeight:Int = 0
    private var kernelRadius:Float = Float.NaN
    private var kernelDensity:Float = Float.NaN
    private val meshBuffers:IntArray = IntArray(7)
    private val meshIndexBuffers:IntArray = IntArray(7)
    private val meshCounts:IntArray = IntArray(7)
    private val backgroundVaos:IntArray = IntArray(7)
    private val foregroundVaos:IntArray = IntArray(7)
    private val backgroundBuffer:Int
    private val foregroundBuffer:Int
    private val backgroundBytes:ByteBuffer? = ByteBuffer.allocateDirect(SLOTS * INSTANCE * 4).order(ByteOrder.nativeOrder())
    private val foregroundBytes:ByteBuffer? = ByteBuffer.allocateDirect(INSTANCE * 4).order(ByteOrder.nativeOrder())
    private val backgroundFloats:FloatBuffer? = backgroundBytes!!.asFloatBuffer()
    private val foregroundFloats:FloatBuffer? = foregroundBytes!!.asFloatBuffer()
    private val mainUniforms:SceneUniforms?
    private val rimUniforms:SceneUniforms?
    private var activeUniforms:SceneUniforms? = null
    private var hdrHeadroom:Float = 1f
    private var hdrReach:Float = 1f
    private val backdropProgram:Int
    private val backdropScreen:Int
    private val backdropOffset:Int
    private val backdropLight:Int
    private val opaqueScreen:Int
    private val opaqueProjection:Int
    private val opaqueSurface:Int
    private val opaqueDark:Int
    private val opaqueLight:Int
    private val opaqueHdr:Int
    private val opaqueMode:Int
    private val opaqueCovers:Int
    private val opaquePreviews:Int
    private val opaqueCoverBlend:Int
    private var coverBlend = FloatArray(100) { 1f }
    private var targetExterior:Boolean = false
    private var opaqueBackground:Boolean = false
    private val depthScreen:Int
    private val depthProjection:Int
    private var targetHdr:Boolean = false
    private var backgroundPacked:Boolean = false
    private var refractionSourceDirty:Boolean = true
    private val backgroundParameterBits:IntArray = IntArray(BACKGROUND_PARAMETERS!!.size)
    private val boundTextures:IntArray = IntArray(8)
    private var count:Int = 0
    private var foregroundCount:Int = 0
    private var width:Int = 0
    private var height:Int = 0
    private var padX:Int = 0
    private var padY:Int = 0
    private var textureWidth:Int = 0
    private var textureHeight:Int = 0
    private var opaqueColor:Int = 0
    private var opaqueDepth:Int = 0
    private var opaqueFbo:Int = 0
    private var attachedSceneColor:Int = 0
    private class SceneUniforms( val program:Int) {
        val screen:Int
        val pixels:Int
        val origin:Int
        val policy:Int
        val dark:Int
        val surface:Int
        val foreground:Int
        val blur:Int
        val backgroundScale:Int
        val projectionOffset:Int
        val sceneRect:Int
        val materialMode:Int
        val frostRadius:Int
        val keyLight:Int
        val materialGeometry:Int
        val hdr:Int
        val hdrReach:Int
        val outputMode:Int
        val sceneTexel:Int
        val samplers:IntArray = IntArray(8)
        val coverBlend:Int
        init{
            screen = GLES30.glGetUniformLocation(program, "u_screen")
            pixels = GLES30.glGetUniformLocation(program, "u_pixels")
            origin = GLES30.glGetUniformLocation(program, "u_origin")
            policy = GLES30.glGetUniformLocation(program, "u_policy")
            dark = GLES30.glGetUniformLocation(program, "u_dark")
            surface = GLES30.glGetUniformLocation(program, "u_surface")
            foreground = GLES30.glGetUniformLocation(program, "u_foreground")
            blur = GLES30.glGetUniformLocation(program, "u_blur")
            backgroundScale = GLES30.glGetUniformLocation(program, "u_background_scale")
            projectionOffset = GLES30.glGetUniformLocation(program, "u_projection_offset")
            sceneRect = GLES30.glGetUniformLocation(program, "u_scene_rect")
            materialMode = GLES30.glGetUniformLocation(program, "u_material_mode")
            frostRadius = GLES30.glGetUniformLocation(program, "u_frost_radius")
            keyLight = GLES30.glGetUniformLocation(program, "u_key_light")
            materialGeometry = GLES30.glGetUniformLocation(program, "u_material_geometry")
            hdr = GLES30.glGetUniformLocation(program, "u_hdr_headroom")
            hdrReach = GLES30.glGetUniformLocation(program, "u_hdr_reach")
            outputMode = GLES30.glGetUniformLocation(program, "u_output_mode")
            sceneTexel = GLES30.glGetUniformLocation(program, "u_scene_texel")
            coverBlend = GLES30.glGetUniformLocation(program, "u_cover_blend[0]")
            val names = arrayOf<String>("u_covers", "u_previews", "u_scene", "u_depth", "u_soft", "u_far", "u_context", "u_frost_scene")
            for (i in names.indices) samplers[i] = GLES30.glGetUniformLocation(program, names[i])
        }
    }
    init{
        val vertex = shader(context, GLES30.GL_VERTEX_SHADER, "shaders/daily_rhine.vert")
        val fragment = shader(context, GLES30.GL_FRAGMENT_SHADER, "shaders/daily_rhine.frag")
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(program))
        val ov = shader(context, GLES30.GL_VERTEX_SHADER, "shaders/daily_rhine.vert")
        val of = shader(context, GLES30.GL_FRAGMENT_SHADER, "shaders/daily_rhine.frag", "#define RHINE_OPAQUE_PASS\n")
        opaqueProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(opaqueProgram, ov)
        GLES30.glAttachShader(opaqueProgram, of)
        GLES30.glLinkProgram(opaqueProgram)
        GLES30.glDeleteShader(ov)
        GLES30.glDeleteShader(of)
        GLES30.glGetProgramiv(opaqueProgram, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(opaqueProgram))
        opaqueScreen = opaqueLocation("u_screen")
        opaqueProjection = opaqueLocation("u_projection_offset")
        opaqueSurface = opaqueLocation("u_surface")
        opaqueDark = opaqueLocation("u_dark")
        opaqueLight = opaqueLocation("u_key_light")
        opaqueHdr = opaqueLocation("u_hdr_headroom")
        opaqueMode = opaqueLocation("u_material_mode")
        opaqueCovers = opaqueLocation("u_covers")
        opaquePreviews = opaqueLocation("u_previews")
        opaqueCoverBlend = opaqueLocation("u_cover_blend[0]")
        val rv = shader(context, GLES30.GL_VERTEX_SHADER, "shaders/daily_rhine.vert")
        val rf = shader(context, GLES30.GL_FRAGMENT_SHADER, "shaders/daily_rhine.frag", "#define RHINE_RIM_PASS\n")
        rimProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(rimProgram, rv)
        GLES30.glAttachShader(rimProgram, rf)
        GLES30.glLinkProgram(rimProgram)
        GLES30.glDeleteShader(rv)
        GLES30.glDeleteShader(rf)
        GLES30.glGetProgramiv(rimProgram, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(rimProgram))
        val dv = shader(context, GLES30.GL_VERTEX_SHADER, "shaders/daily_rhine.vert")
        val df = shader(context, GLES30.GL_FRAGMENT_SHADER, "shaders/daily_rhine.frag", "#define RHINE_DEPTH_PASS\n")
        depthProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(depthProgram, dv)
        GLES30.glAttachShader(depthProgram, df)
        GLES30.glLinkProgram(depthProgram)
        GLES30.glDeleteShader(dv)
        GLES30.glDeleteShader(df)
        GLES30.glGetProgramiv(depthProgram, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(depthProgram))
        depthScreen = GLES30.glGetUniformLocation(depthProgram, "u_screen")
        depthProjection = GLES30.glGetUniformLocation(depthProgram, "u_projection_offset")
        for (p in intArrayOf(program, rimProgram, opaqueProgram)) AlbumLyricsChip.configure(p)
        mainUniforms = SceneUniforms(program)
        rimUniforms = SceneUniforms(rimProgram)
        activeUniforms = mainUniforms
        filterProgram = filterProgram(context, "shaders/daily_context_gaussian.frag")
        downProgram = filterProgram(context, "shaders/daily_context_downsample.frag")
        filterImage = GLES30.glGetUniformLocation(filterProgram, "u_image")
        filterStep = GLES30.glGetUniformLocation(filterProgram, "u_step")
        filterKernel = GLES30.glGetUniformLocation(filterProgram, "u_kernel[0]")
        downImage = GLES30.glGetUniformLocation(downProgram, "u_image")
        downTexel = GLES30.glGetUniformLocation(downProgram, "u_texel")
        backdropProgram = filterProgram(context, "shaders/daily_rhine_backdrop.vert", "shaders/daily_rhine_backdrop.frag")
        backdropScreen = GLES30.glGetUniformLocation(backdropProgram, "u_screen")
        backdropOffset = GLES30.glGetUniformLocation(backdropProgram, "u_projection_offset")
        backdropLight = GLES30.glGetUniformLocation(backdropProgram, "u_key_light")
        val full = IntArray(1)
        GLES30.glGenVertexArrays(1, full, 0)
        filterVao = full[0]

        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        backgroundBuffer = ids[0]
        foregroundBuffer = ids[1]
        val mesh = ByteBuffer.allocateDirect(MESH_FLOATS * 4).order(ByteOrder.nativeOrder())
        val indices = ByteBuffer.allocateDirect(MESH_FLOATS / 6 * 2).order(ByteOrder.nativeOrder())
        GLES30.glGenBuffers(7, meshBuffers, 0)
        GLES30.glGenBuffers(7, meshIndexBuffers, 0)
        GLES30.glGenVertexArrays(7, backgroundVaos, 0)
        GLES30.glGenVertexArrays(7, foregroundVaos, 0)
        for (k in 0..6)
        {
            val packed = SpatialNative.dailyRhineIndexedMesh(k, mesh, indices)
            val vertices = packed.toInt()
            val n = (packed.ushr(32)).toInt()
            if (packed < 0 || vertices <= 0 || vertices > MESH_FLOATS / 6 || n <= 0 || n > MESH_FLOATS / 6 || n % 3 != 0) throw IllegalStateException("Rhine indexed mesh unavailable")
            meshCounts[k] = n
            mesh.position(0)
            indices.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshBuffers[k])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices * 6 * 4, mesh, GLES30.GL_STATIC_DRAW)
            vao(backgroundVaos[k], meshBuffers[k], meshIndexBuffers[k], backgroundBuffer)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, n * 2, indices, GLES30.GL_STATIC_DRAW)
            vao(foregroundVaos[k], meshBuffers[k], meshIndexBuffers[k], foregroundBuffer)
        }
    }
    fun setHdrHeadroom(value:Float) {
        if (hdrHeadroom != value) {
            hdrHeadroom = value
            hdrReach = Math.sqrt((value).toDouble()).toFloat()
            invalidateRefractionSource()
        }
    }
    private fun opaqueLocation(name:String?):Int {
        return GLES30.glGetUniformLocation(opaqueProgram, name)
    }

    private fun filterRefraction(source:Int, radius:Float, density:Float) {
        if (radius != kernelRadius || density != kernelDensity)
        {
            // The existing Rust kernel is calibrated to 1/8 resolution; x4 gives 1/2 here.
            if (!SpatialNative.dailyContextBlurKernel(radius * 4f, density, (kernelBytes)!!)) throw IllegalStateException("Invalid glass blur kernel")
            kernelRadius = radius
            kernelDensity = density
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindVertexArray(filterVao)
        GLES30.glViewport(0, 0, frostWidth, frostHeight)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frostFbos[0])
        GLES30.glUseProgram(downProgram)
        GLES30.glUniform1i(downImage, 0)
        GLES30.glUniform2f(downTexel, 1f / textureWidth, 1f / textureHeight)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glUseProgram(filterProgram)
        GLES30.glUniform1i(filterImage, 0)
        kernel!!.position(0)
        GLES30.glUniform2fv(filterKernel, 7, kernel)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frostFbos[1])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frostTextures[0])
        GLES30.glUniform2f(filterStep, 1f / frostWidth, (0).toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frostFbos[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frostTextures[1])
        GLES30.glUniform2f(filterStep, (0).toFloat(), 1f / frostHeight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }
    fun updateBackground(frame:ByteBuffer?, bindings:ByteBuffer?):Boolean {
        val packed = SpatialNative.dailyRhinePackChanged((frame)!!, (bindings)!!, (backgroundBytes)!!, if (backgroundPacked) count else -1)
        if (packed < 0) throw IllegalStateException("Rhine instance packing failed")
        count = packed.toInt()
        var changed = (packed and (1L shl 32)) != 0L
        refractionSourceDirty = refractionSourceDirty or ((packed and (1L shl 33)) != 0L)
        opaqueBackground = count > 0 && backgroundFloats!!.get(23) > 1.5f
        if (changed) upload(backgroundBuffer, backgroundFloats, count)
        // Uniforms can change without changing instances. Pixel uploads, palette,
        // target recreation and warm-up are forced by the owner independently.
        changed = changed or !backgroundPacked
        for (i in BACKGROUND_PARAMETERS!!.indices)
        {
            val bits = frame.getInt((DailySpatialProtocol.RHINE + BACKGROUND_PARAMETERS[i]) * 4)
            val parameterChanged = bits != backgroundParameterBits[i]
            changed = changed or parameterChanged
            refractionSourceDirty = refractionSourceDirty or parameterChanged
            backgroundParameterBits[i] = bits
        }
        backgroundPacked = true
        return changed
    }

    fun invalidateRefractionSource() {
        refractionSourceDirty = true
    }
    fun setCoverBlend(values: FloatArray) {
        coverBlend = values
    }
    fun updateForeground(frame:ByteBuffer?, bindings:ByteBuffer?) {
        foregroundCount = SpatialNative.dailyRhinePack((frame)!!, (bindings)!!, (foregroundBytes)!!, true)
        if (foregroundCount < 0) throw IllegalStateException("Rhine foreground packing failed")
        if (foregroundCount > 0) upload(foregroundBuffer, foregroundFloats, foregroundCount)
    }
    private fun textures(covers:Int, previews:Int, scene:Int, depth:Int, soft:Int, far:Int, context:Int) {
        boundTextures[0] = covers
        boundTextures[1] = previews
        boundTextures[2] = scene
        boundTextures[3] = depth
        boundTextures[4] = soft
        boundTextures[5] = far
        boundTextures[6] = context
        boundTextures[7] = if (frostTextures[0] != 0) frostTextures[0] else scene
        for (i in boundTextures.indices)
        {
            // The specialized rim program only consumes its depth and Gaussian
            // source. Do not cross JNI to bind textures removed by the linker.
            if (activeUniforms!!.samplers[i] < 0) continue
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(if (i == 0) GLES30.GL_TEXTURE_2D_ARRAY else GLES30.GL_TEXTURE_2D, boundTextures[i])
            GLES30.glUniform1i(activeUniforms!!.samplers[i], i)
        }
    }
    private fun uniforms(frame:FloatArray?, density:Float, isDark:Boolean, left:Int, bottom:Int, front:Boolean, rim:Boolean = false) {
        val u = if (rim) rimUniforms else mainUniforms
        activeUniforms = u
        val a = DailySpatialProtocol.ACTION
        GLES30.glUseProgram(u!!.program)
        if (u.coverBlend >= 0) GLES30.glUniform4fv(u.coverBlend, coverBlend.size / 4, coverBlend, 0)
        GLES30.glUniform2f(u.sceneTexel, 1f / textureWidth, 1f / textureHeight)
        GLES30.glUniform1f(u.hdr, if (hdrSurface) hdrHeadroom else 1f)
        GLES30.glUniform1f(u.hdrReach, if (hdrSurface) hdrReach else 1f)
        GLES30.glUniform1f(u.outputMode, (0).toFloat())
        GLES30.glUniform4f(u.keyLight, frame!![DailySpatialProtocol.RHINE + 8], frame[DailySpatialProtocol.RHINE + 9], frame[DailySpatialProtocol.RHINE + 10], frame[DailySpatialProtocol.RHINE + 11])
        GLES30.glUniform1f(u.materialMode, frame[DailySpatialProtocol.RHINE + 5])
        GLES30.glUniform2f(u.materialGeometry, frame[DailySpatialProtocol.RHINE + 13], frame[DailySpatialProtocol.RHINE + 14])
        GLES30.glUniform1f(u.frostRadius, frame[DailySpatialProtocol.RHINE + 6])
        GLES30.glUniform2f(u.screen, (if (front) width else textureWidth) / density, (if (front) height else textureHeight) / density)
        GLES30.glUniform2f(u.pixels, (if (front) width else textureWidth).toFloat(), (if (front) height else textureHeight).toFloat())
        GLES30.glUniform2f(u.projectionOffset, (if (front) 0 else padX / density).toFloat(), (if (front) 0 else padY / density).toFloat())
        GLES30.glUniform4f(u.sceneRect, padX / textureWidth.toFloat(), padY / textureHeight.toFloat(), width / textureWidth.toFloat(), height / textureHeight.toFloat())
        GLES30.glUniform2f(u.origin, (left).toFloat(), (bottom).toFloat())
        GLES30.glUniform1f(u.dark, (if (isDark) 1 else 0).toFloat())
        GLES30.glUniform1f(u.foreground, (if (front) 1 else 0).toFloat())
        GLES30.glUniform1f(u.blur, frame[a + 1])
        GLES30.glUniform1f(u.backgroundScale, frame[a + 127])
        GLES30.glUniform4f(u.policy, frame[26], frame[27], frame[28], frame[30])
    }
    fun background(frame:FloatArray?, density:Float, isDark:Boolean, target:Int, coverTexture:Int, previewTexture:Int, sceneColor:Int, w:Int, h:Int, px:Int, py:Int) {
        val matte = Math.abs(frame!![DailySpatialProtocol.RHINE + 5] - 1f) < .1f || Math.abs(frame[DailySpatialProtocol.RHINE + 5] - 3f) < .1f
        val exterior = frame[DailySpatialProtocol.RHINE + 5] > 1.5f
        targets(w, h, px, py, !matte, hdrSurface && hdrHeadroom > 1f, exterior, sceneColor)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        if (opaqueBackground) GLES30.glDisable(GLES30.GL_BLEND)
        if (matte)
        {
            // Opaque sleeves render once with actual depth writes. No glass source allocation,
            // inner-core pass, scene copy, refraction or scattering work in this mode.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
            GLES30.glViewport(0, 0, textureWidth, textureHeight)
            val bg = (if (isDark) 0 else 1).toFloat()
            GLES30.glClearColor(bg, bg, bg, (1).toFloat())
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (!opaqueBackground) drawBackdrop(frame, density, isDark)
            uniforms(frame, density, isDark, 0, 0, false)
            textures(coverTexture, previewTexture, previewTexture, previewTexture, previewTexture, previewTexture, previewTexture)
            GLES30.glUniform1f(activeUniforms!!.surface, (1).toFloat())
            GLES30.glBindVertexArray(backgroundVaos[1])
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[1], GLES30.GL_UNSIGNED_SHORT, 0, count)
            if (frame[DailySpatialProtocol.RHINE + 5] > 2.5f) {
                GLES30.glUniform1f(activeUniforms!!.surface, (2).toFloat())
                GLES30.glBindVertexArray(backgroundVaos[3])
                GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[3], GLES30.GL_UNSIGNED_SHORT, 0, count)
            }
            if (opaqueBackground) drawBackdrop(frame, density, isDark)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            return
        }
        // Both FBOs share sceneColor, but own distinct depth textures. Stable opaque
        // scenes write the refraction source's depth directly, eliminating the full-
        // resolution depth blit. The final target rebuilds depth with cheap geometry.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (exterior && !opaqueBackground) target else opaqueFbo)
        GLES30.glViewport(0, 0, textureWidth, textureHeight)
        val bg = (if (isDark) 0 else 1).toFloat()
        GLES30.glClearColor(bg, bg, bg, (1).toFloat())
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (!opaqueBackground) drawBackdrop(frame, density, isDark)
        if (exterior)
        {
            if (opaqueBackground)
            {
                beginDepth(density)
                if (frame[DailySpatialProtocol.RHINE + 5] > 3.5f) drawBackgroundMesh(4)
                drawBackgroundMesh(3)
                endDepth()
            }
            bindOpaque(frame, density, isDark, coverTexture, previewTexture)
            if (frame[DailySpatialProtocol.RHINE + 5] > 3.5f) {
                GLES30.glUniform1f(opaqueSurface, (0).toFloat())
                GLES30.glBindVertexArray(backgroundVaos[4])
                GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[4], GLES30.GL_UNSIGNED_SHORT, 0, count)
            }
            GLES30.glUniform1f(opaqueSurface, (2).toFloat())
            GLES30.glBindVertexArray(backgroundVaos[3])
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[3], GLES30.GL_UNSIGNED_SHORT, 0, count)
            GLES30.glDepthMask(true)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            if (opaqueBackground) drawBackdrop(frame, density, isDark)
            if (!opaqueBackground)
            {
                // Fades keep their original painter/compositing path and depth snapshot.
                // The shared colour attachment is untouched by this depth-only blit.
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, target)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, opaqueFbo)
                GLES30.glBlitFramebuffer(0, 0, textureWidth, textureHeight, 0, 0, textureWidth, textureHeight, GLES30.GL_DEPTH_BUFFER_BIT, GLES30.GL_NEAREST)
            }
            // Incoming area lights shade the rim only; they do not change this
            // core/print source. Keep its exact Gaussian result while they move.
            if (refractionSourceDirty)
            {
                filterRefraction(sceneColor, frame[DailySpatialProtocol.RHINE + 6], density)
                refractionSourceDirty = false
            }
        }
        else
        {
            uniforms(frame, density, isDark, 0, 0, false)
            textures(coverTexture, previewTexture, previewTexture, previewTexture, previewTexture, previewTexture, previewTexture)
            GLES30.glUniform1f(activeUniforms!!.surface, (0).toFloat())
            GLES30.glBindVertexArray(backgroundVaos[0])
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[0], GLES30.GL_UNSIGNED_SHORT, 0, count)
            if (opaqueBackground) drawBackdrop(frame, density, isDark)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, opaqueFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target)
            GLES30.glBlitFramebuffer(0, 0, textureWidth, textureHeight, 0, 0, textureWidth, textureHeight, GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT, GLES30.GL_NEAREST)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(0, 0, textureWidth, textureHeight)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        val shell = if (frame[DailySpatialProtocol.RHINE + 5] > 3.5f) 6 else 1
        // Source depth stays immutable for refraction. Reconstruct final depth instead
        // of copying it: same vertices and invariant gl_Position, no colour writes or
        // optical work. sceneColor already contains the source through the shared FBO.
        if (opaqueBackground && exterior)
        {
            beginDepth(density)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            if (frame[DailySpatialProtocol.RHINE + 5] > 3.5f) drawBackgroundMesh(4)
            drawBackgroundMesh(3)
            drawBackgroundMesh(shell)
            endDepth()
        }
        uniforms(frame, density, isDark, 0, 0, false, frame[DailySpatialProtocol.RHINE + 5] > 3.5f)
        // The colour pass samples distinct Gaussian/depth targets; no framebuffer feedback.
        textures(coverTexture, previewTexture, if (exterior) previewTexture else opaqueColor, opaqueDepth, previewTexture, previewTexture, previewTexture)
        GLES30.glUniform1f(activeUniforms!!.surface, (1).toFloat())
        if (opaqueBackground)
        GLES30.glDisable(GLES30.GL_BLEND)
        else
        {
            GLES30.glDepthMask(exterior)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        drawBackgroundMesh(shell)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }

    private fun drawBackdrop(frame:FloatArray?, density:Float, dark:Boolean) {
        if (dark) return
        GLES30.glUseProgram(backdropProgram)
        GLES30.glBindVertexArray(filterVao)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUniform2f(backdropScreen, textureWidth / density, textureHeight / density)
        GLES30.glUniform2f(backdropOffset, padX / density, padY / density)
        val r = DailySpatialProtocol.RHINE
        GLES30.glUniform4f(backdropLight, frame!![r + 8], frame[r + 9], frame[r + 10], frame[r + 11])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        if (!opaqueBackground) GLES30.glEnable(GLES30.GL_BLEND)
    }
    private fun drawBackgroundMesh(mesh:Int) {
        GLES30.glBindVertexArray(backgroundVaos[mesh])
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[mesh], GLES30.GL_UNSIGNED_SHORT, 0, count)
    }
    private fun beginDepth(density:Float) {
        GLES30.glUseProgram(depthProgram)
        GLES30.glUniform2f(depthScreen, textureWidth / density, textureHeight / density)
        GLES30.glUniform2f(depthProjection, padX / density, padY / density)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    }
    private fun endDepth() {
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(false)
        GLES30.glDepthFunc(GLES30.GL_EQUAL)
    }

    private fun bindOpaque(frame:FloatArray?, density:Float, isDark:Boolean, covers:Int, previews:Int) {
        GLES30.glUseProgram(opaqueProgram)
        GLES30.glUniform4fv(opaqueCoverBlend, coverBlend.size / 4, coverBlend, 0)
        GLES30.glUniform2f(opaqueScreen, textureWidth / density, textureHeight / density)
        GLES30.glUniform2f(opaqueProjection, padX / density, padY / density)
        GLES30.glUniform1f(opaqueDark, (if (isDark) 1 else 0).toFloat())
        GLES30.glUniform1f(opaqueHdr, if (hdrSurface) hdrHeadroom else 1f)
        GLES30.glUniform1f(opaqueMode, frame!![DailySpatialProtocol.RHINE + 5])
        val r = DailySpatialProtocol.RHINE
        GLES30.glUniform4f(opaqueLight, frame[r + 8], frame[r + 9], frame[r + 10], frame[r + 11])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, covers)
        GLES30.glUniform1i(opaqueCovers, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previews)
        GLES30.glUniform1i(opaquePreviews, 1)
    }
    fun foreground(frame:FloatArray?, density:Float, isDark:Boolean, target:Int, left:Int, bottom:Int, coverTexture:Int, previewTexture:Int, scene:Int, depth:Int, soft:Int, far:Int, context:Int) {
        if (foregroundCount == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(-left, -bottom, width, height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        uniforms(frame, density, isDark, left, bottom, true)
        GLES30.glUniform1f(activeUniforms!!.outputMode, (if (hdrSurface) (if (target == 0) 1 else 2) else 0).toFloat())
        textures(coverTexture, previewTexture, scene, depth, soft, far, context)
        if (frame!![DailySpatialProtocol.RHINE + 5] > 3.5f) {
            GLES30.glUniform1f(activeUniforms!!.surface, (0).toFloat())
            GLES30.glBindVertexArray(foregroundVaos[4])
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[4], GLES30.GL_UNSIGNED_SHORT, 0, foregroundCount)
        }
        if (frame[DailySpatialProtocol.RHINE + 5] < .5f) {
            GLES30.glUniform1f(activeUniforms!!.surface, (0).toFloat())
            GLES30.glBindVertexArray(foregroundVaos[0])
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[0], GLES30.GL_UNSIGNED_SHORT, 0, foregroundCount)
        }
        GLES30.glUniform1f(activeUniforms!!.surface, (1).toFloat())
        GLES30.glBindVertexArray(foregroundVaos[2])
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[2], GLES30.GL_UNSIGNED_SHORT, 0, foregroundCount)
        if (frame[DailySpatialProtocol.RHINE + 5] > 1.5f) {
            GLES30.glUniform1f(activeUniforms!!.surface, (2).toFloat())
            GLES30.glBindVertexArray(foregroundVaos[3])
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[3], GLES30.GL_UNSIGNED_SHORT, 0, foregroundCount)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }
    private fun texture(w:Int, h:Int, depth:Boolean):Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, if (depth) GLES30.GL_DEPTH_COMPONENT24 else (if (targetHdr) GLES30.GL_RGBA16F else GLES30.GL_RGBA8), w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (depth) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, if (depth) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    // Texture names can be recycled after deletion while an old attachment keeps
    // the previous object alive. The owner invalidates even when the new ID matches.
    fun invalidateSceneAttachment() {
        invalidateRefractionSource()
        attachedSceneColor = -1
        // Do not retain the previous full-size colour image while another route
        // is active. The next background call attaches its current scene image.
        if (targetExterior && opaqueFbo != 0)
        {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, opaqueFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0)
        }
    }
    private fun targets(w:Int, h:Int, px:Int, py:Int, glass:Boolean, floating:Boolean, exterior:Boolean, sceneColor:Int) {
        if (width != w || height != h || padX != px || padY != py || targetHdr != floating || targetExterior != exterior)
        {
            invalidateRefractionSource()
            if (opaqueFbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(opaqueFbo), 0)
                GLES30.glDeleteTextures(2, intArrayOf(opaqueColor, opaqueDepth), 0)
                opaqueDepth = 0
                opaqueColor = opaqueDepth
                opaqueFbo = opaqueColor
            }
            if (frostFbos[0] != 0) {
                GLES30.glDeleteFramebuffers(2, frostFbos, 0)
                GLES30.glDeleteTextures(2, frostTextures, 0)
                java.util.Arrays.fill(frostFbos, 0)
                java.util.Arrays.fill(frostTextures, 0)
            }
            targetHdr = floating
            targetExterior = exterior
            width = w
            height = h
            padX = px
            padY = py
            textureWidth = w + 2 * px
            textureHeight = h + 2 * py
        }
        if (!glass) return
        if (opaqueFbo != 0)
        {
            // Scene targets can be recreated on route/theme changes without changing
            // dimensions. Never keep the old, deleted-but-still-attached colour image.
            if (exterior && attachedSceneColor != sceneColor)
            {
                invalidateRefractionSource()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, opaqueFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneColor, 0)
                attachedSceneColor = sceneColor
            }
            return
        }
        invalidateRefractionSource()
        opaqueColor = if (exterior) 0 else texture(textureWidth, textureHeight, false)
        opaqueDepth = texture(textureWidth, textureHeight, true)
        val id = IntArray(1)
        GLES30.glGenFramebuffers(1, id, 0)
        opaqueFbo = id[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, opaqueFbo)
        attachedSceneColor = if (exterior) sceneColor else 0
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, if (exterior) sceneColor else opaqueColor, 0)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, opaqueDepth, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("Rhine refraction target incomplete")
        frostWidth = Math.max(1, (textureWidth + 1) / 2)
        frostHeight = Math.max(1, (textureHeight + 1) / 2)
        GLES30.glGenFramebuffers(2, frostFbos, 0)
        for (i in 0..1) {
            frostTextures[i] = texture(frostWidth, frostHeight, false)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frostFbos[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, frostTextures[i], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("Glass filter target incomplete")
        }

    }

    companion object {
        private val SLOTS:Int = DailySpatialProtocol.SLOTS
        private val INSTANCE:Int = 52
        private val MESH_FLOATS:Int = 7776
        private val BACKGROUND_PARAMETERS:IntArray? = intArrayOf(5, 6, 8, 9, 10, 11, 13, 14)
        @Throws(Exception::class)
        private fun shader(context:Context?, kind:Int, path:String?, defines:String? = ""):Int {
            val source = DailySpatialShaders.load(context, path, defines)
            val shader = GLES30.glCreateShader(kind)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException(GLES30.glGetShaderInfoLog(shader))
            return shader
        }
        @Throws(Exception::class)
        private fun filterProgram(context:Context?, fragment:String?):Int {
            return filterProgram(context, "shaders/daily_fullscreen.vert", fragment)
        }
        @Throws(Exception::class)
        private fun filterProgram(context:Context?, vertex:String?, fragment:String?):Int {
            val v = shader(context, GLES30.GL_VERTEX_SHADER, vertex)
            val f = shader(context, GLES30.GL_FRAGMENT_SHADER, fragment)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(p))
            return p
        }
        private fun vao(vao:Int, mesh:Int, indices:Int, instances:Int) {
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh)
            for (i in 0..1) {
                GLES30.glEnableVertexAttribArray(i)
                GLES30.glVertexAttribPointer(i, 3, GLES30.GL_FLOAT, false, 24, i * 12)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instances)
            for (i in 0..12) {
                val at = i + 2
                GLES30.glEnableVertexAttribArray(at)
                GLES30.glVertexAttribPointer(at, 4, GLES30.GL_FLOAT, false, INSTANCE * 4, i * 16)
                GLES30.glVertexAttribDivisor(at, 1)
            }
        }
        private fun upload(buffer:Int, floats:FloatBuffer?, count:Int) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            floats!!.position(0)
            floats.limit(count * INSTANCE)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, Math.max(INSTANCE, count * INSTANCE) * 4, null, GLES30.GL_STREAM_DRAW)
            if (count > 0) GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * INSTANCE * 4, floats)
            floats.clear()
        }
    }
}
