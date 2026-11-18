package com.lab.hv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RecordingCanvas;
import android.graphics.Shader.TileMode;
import android.os.Process;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.view.View.MeasureSpec;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.OverScroller;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.lab.hv.util.AppStrings;
import com.lab.hv.util.HdrColor;
import com.lab.hv.util.LyricsStyleStore;
import io.github.techuouo520.folia.R.string;
import com.lab.xui.Interpolator.BezierControl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NowPlayingLyricsTickerView extends FrameLayout {

   public static final class CompactLyricSnapshot {
      @NonNull public final String text;
      public final float revealedCodePoints;
      public final boolean karaoke;

      CompactLyricSnapshot(@NonNull String text, float revealedCodePoints, boolean karaoke) {
         this.text = text;
         this.revealedCodePoints = Math.max(0.0F, revealedCodePoints);
         this.karaoke = karaoke;
      }
   }
   /** A mixed-format translation may start at most this long before its word-by-word line. */
   private static final long MIXED_TRANSLATION_LEAD_MS = 1000L;
   private static final Pattern LRC_TIME_PATTERN = Pattern.compile("[\\[<](?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:[\\.:,](\\d{1,3}))?[\\]>]");
   private static final long LINE_SCROLL_DURATION_MS = 600L;
   /** After a manual scroll ends (finger up, fling settled) the lyrics follow the song again. */
   private static final long MANUAL_SCROLL_RESUME_DELAY_MS = 600L;
   private static final int ROW_CHAR_COUNT = 8;
   private static final float ACTIVE_ROW_CONTROL_MAGNIFY_RATIO = 0.04F;
   private static final float ACTIVE_ANCHOR_ROW_INDEX = 1.35F;
   private static final float WIDE_ACTIVE_ANCHOR_ROW_INDEX = 4.0F;
   private static final float CLICK_MOVE_CANCEL_DP = 6.0F;
   private static final float LAG_VISUAL_STRENGTH = 1.8F;
   private static final float LAG_PULL_FACTOR = 0.24F;
   private static final float LAG_MAX_OFFSET_ROW_RATIO = 0.72F;
   private static final float LAG_MAX_OFFSET_VIEWPORT_RATIO = 0.16F;
   private static final float LAG_ROW_DISTANCE_DECAY = 0.82F;
   private static final float LAG_MIN_ROW_STRENGTH = 0.2F;
   private static final float LAG_RELEASE_TRIGGER_PROGRESS = 0.36F;
   private static final long LAG_RELEASE_STAGGER_MS = 38L;
   private static final long LAG_RETURN_DURATION_MS = 1400L;
   /** A line change's scroll (920 ms: 800 felt a little quick for line-by-line following). */
   private static final long TRACK_SCROLL_ANIM_MS = 920L;
   private static final float TRACK_EDGE_SPACER_DP = 300.0F;
   private static final float NON_TARGET_ALPHA = 0.3F;
   /** Each line beyond the next one is this much fainter, down to this share of the standby alpha. */
   private static final float DISTANCE_ALPHA_STEP = 0.86F;
   private static final float DISTANCE_ALPHA_FLOOR = 0.35F;
   private static final float TRANSLATION_ALPHA = 0.7F;
   // Shared by karaoke rows waiting off-anchor and unrevealed glyphs in the target row.
   private static final float KARAOKE_STANDBY_ALPHA = 0.3F;
   private static final long KARAOKE_TERMINAL_SCAN_MS = 360L;
   private static final long KARAOKE_SCROLL_COLLAPSE_GAP_MAX_MS = KARAOKE_TERMINAL_SCAN_MS * 2L;
   private static final int KARAOKE_SCROLL_COLLAPSE_MAX_SKIPPED_SOURCES = 2;
   private static final float KARAOKE_SCAN_FEATHER_TEXT_RATIO = 0.82F;
   private static final long KARAOKE_GLYPH_LIFT_MIN_DURATION_MS = 500L;
   private static final float KARAOKE_GLYPH_AVERAGE_DURATION_HEADROOM_RATIO = 1.20F;
   private static final long KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS = 900L;
   private static final float KARAOKE_GLYPH_LONG_NOTE_EXTRA_LIFT_DP = 4.5F;
   private static final float KARAOKE_GLYPH_LONG_NOTE_BOUNCE_DP = 1.0F;
   private static final float KARAOKE_GLYPH_LONG_NOTE_MAX_LIFT_MULTIPLIER = 2.5F;
   private static final float KARAOKE_GLYPH_LONG_NOTE_GLOW_INNER_RADIUS_DP = 3.2F;
   private static final float KARAOKE_GLYPH_LONG_NOTE_GLOW_OUTER_RADIUS_DP = 8.8F;
   private static final float KARAOKE_GLYPH_LONG_NOTE_VISUAL_PADDING_DP = 30.0F;
   private static final long KARAOKE_GLYPH_LONG_NOTE_GLOW_RISE_MAX_MS = 420L;
   private static final long KARAOKE_GLYPH_LONG_NOTE_FALL_MS = 360L;
   private static final long KARAOKE_GLYPH_LONG_NOTE_SETTLE_MS = 220L;
   /** A held word (two letters or more) rises whole, this much higher and with this much more rebound. */
   private static final float KARAOKE_WORD_LONG_NOTE_LIFT_SCALE = 1.2F;
   private static final int KARAOKE_GLYPH_LIFT_TRAILING_SOURCE_WINDOW = 3;
   private static final float ROW_RENDER_ALPHA_EPSILON = 0.003F;
   private static final float ROW_RENDER_VIEWPORT_EXTRA_ROWS = 3.0F;
   private static final long PLAYBACK_VISUAL_SYNC_SNAP_MS = 420L;
   private static final long PLAYBACK_VISUAL_SYNC_MAX_STEP_MS = 12L;
   private static final float PLAYBACK_VISUAL_SYNC_CORRECTION_RATIO = 0.18F;
   private static final long KARAOKE_POSITION_BACKSTEP_TOLERANCE_MS = 420L;
   private static final long TIMED_LYRICS_IDLE_WAKE_MS = 1000L;
   // Same visual anchor as the old 270ms advance on the original lyrics curve.
   private static final float KARAOKE_SCROLL_SYNC_VISUAL_PROGRESS = 0.6241424F;
   private static final int KARAOKE_SCROLL_SYNC_SEARCH_STEPS = 20;
   private static final long TRANSLATION_PAIR_WINDOW_MS = 140L;
   private static final float NEXT_PREPARE_ALPHA = 0.6F;
   private static final float NEXT_PREPARE_WINDOW_RATIO = 0.5F;
   private static final float OUTGOING_ALPHA_DROP_RATIO = 0.62F;
   private static final float PASSED_SOURCE_MIN_SCALE = 0.5F;
   private static final long PRESENTATION_ENTER_DURATION_MS = 640L;
   private static final long PRESENTATION_EXIT_DURATION_MS = 360L;
   /** Share of the run over which later lines start after earlier ones. */
   private static final float PRESENTATION_STAGGER = 0.42F;
   private static final float PRESENTATION_OVERSHOOT = 0.9F;
   private static final float PRESENTATION_RISE_DP = 30.0F;
   private static final long NON_LYRIC_HINT_EARLY_MS = 140L;
   private static final long NON_LYRIC_HINT_SHOW_MS = 2200L;
   private static final long NON_LYRIC_HINT_FADE_MS = 260L;
   private static final float NON_LYRIC_HINT_ALPHA = 0.68F;
   private static final float NON_LYRIC_HINT_TEXT_SP = 14.0F;
   private static final float NON_LYRIC_HINT_WEIGHT_DP = 0.18F;
   private static final float NON_LYRIC_HINT_TOP_OFFSET_DP = 6.0F;
   private static final float NON_LYRIC_HINT_EDGE_PAD_DP = 1.4F;
   private static final String[] PERFORMER_METADATA_HEAD_KEYWORDS = new String[]{
         "词", "作词", "填词", "词作者", "词曲", "歌词", "曲", "作曲", "曲作者",
         "编曲", "配器", "歌手", "演唱", "演唱者", "制作", "制作人",
         "监制", "混音", "母带", "录音", "录音师", "和声编写", "和声设计", "声乐指导",
         "原唱", "翻唱", "出品", "发行", "版权", "鸣谢", "题字", "封面", "摄影",
         "lyrics", "lyricist", "writtenby", "composer", "composedby", "arranger",
         "arrangedby", "producer", "producedby", "executiveproducer", "mix", "mixing",
         "mixedby", "master", "mastering", "masteredby", "recording", "recordedby",
         "engineer", "vocalproducer", "vocals", "vocal", "singer", "artist", "performer",
         "performedby", "copyright"
   };
   private static final String[] PERFORMER_SECTION_HEAD_KEYWORDS = new String[]{
         "主歌", "副歌", "前奏", "间奏", "尾奏", "桥段", "导歌", "念白", "对白",
         "verse", "preverse", "bridge", "hook", "intro", "interlude", "outro",
         "refrain", "part", "section", "spoken", "dialogue"
   };
   private static final float TOUCH_PRESS_CARD_CONTENT_MARGIN_DP = 3.0F;
   private static final float TOUCH_PRESS_CARD_HORIZONTAL_PAD_DP = 14.0F;
   private static final float TOUCH_PRESS_CARD_TOP_PAD_DP = 8.0F;
   private static final float TOUCH_PRESS_CARD_BOTTOM_PAD_DP = 9.0F;
   private static final float TOUCH_PRESS_CARD_CORNER_DP = 18.0F;
   private static final float TOUCH_PRESS_ROW_SINK_DP = 1.8F;
   private static final float KARAOKE_GLYPH_REST_OFFSET_DP = 2.0F;
   private static final long TOUCH_PRESS_IN_DURATION_MS = 135L;
   private static final long TOUCH_PRESS_OUT_DURATION_MS = 170L;
   private static final float ROW_BLUR_STEP_DP = 1.15F;
   private static final float ROW_BLUR_MAX_DP = 8.5F;
   private static final float ROW_BLUR_BOUNDS_OVERFLOW_RATIO = 3.0F;
   private static final int BLUR_VIEWPORT_EXTRA_ROWS = 2;
   private static final int BLUR_DYNAMIC_SOURCE_RADIUS = 1;
   private static final float MAIN_WEIGHT_STRENGTH_DP = 0.85F;
   private static final float TRANSLATION_WEIGHT_STRENGTH_DP = 0.0F;
   private static final float EDGE_FADE_TOP_DP = 26.0F;
   private static final float EDGE_FADE_BOTTOM_DP = 50.0F;
   private static final long INTRO_PREP_MIN_IDLE_START_MS = 10000L;
   private static final long INTRO_PREP_MIN_IDLE_MID_MS = 25000L;
   private static final long INTRO_PREP_COUNTDOWN_MS = 4000L;
   private static final long TRANSLATION_TOGGLE_ANIM_MS = 500L;
   private static final long TRANSLATION_TOGGLE_ROW_STAGGER_MS = 0L;
   private static final float INTRO_PREP_FINAL_SCALE_BOOST = 0.17F;
   private static final long INTRO_PREP_FINAL_WINDOW_MIN_MS = 900L;
   private static final long INTRO_PREP_FINAL_WINDOW_MAX_MS = 1600L;
   private static final long PREP_REVEAL_LEAD_MS = TRACK_SCROLL_ANIM_MS;
   private static final float INTRO_PREP_DOT_BASE_SCALE = 0.6F;
   private static final float INTRO_PREP_DOT_REST_BLUR_DP = 5.0F;
   private static final float INTRO_PREP_DOT_WAVE_SCALE = 0.06F;
   private static final float INTRO_PREP_DOT_ACTIVE_BOOST = 0.1F;
   private static final float INTRO_PREP_DOT_IDLE_ALPHA_BASE = 0.16F;
   private static final float INTRO_PREP_DOT_IDLE_ALPHA_WAVE = 0.08F;
   private static final float INTRO_PREP_FINAL_VANISH_SCALE = 0.05F;
   private static final float INTRO_PREP_FINAL_LIFT_PX = 2.2F;
   private static final String[] NON_LYRIC_HEAD_KEYWORDS;
   private static final String[] NON_LYRIC_PHRASE_KEYWORDS;
   private final FrameLayout trackContainer;
   private final PressedRowCardView pressedRowCardView;
   private final SPText nonLyricHintView;
   private final List<TimedLine> timedLines;
   private final List<TimedLine> timedNonLyricLines;
   private final List<String> timedTranslations;
   private final List<KaraokeTimeline> timedTranslationTimelines;
   private final List<String> plainLines;
   private final List<String> plainTranslations;
   private final List<RowHolder> rowHolders;
   private final List<SourceGroup> sourceGroups;
   /** The groups of {@link #sourceGroups} with a prep countdown, in the same order. */
   private final List<SourceGroup> prepSourceGroups;
   private final ArrayList<CharRowFrame> mainRowPool;
   private final ArrayList<CharRowFrame> translationRowPool;
   private final ArrayList<RowHolder> lagTrackedRows;
   /** The rows' lag behind the track (see clearRowLagAnimations and below). */
   private LyricLagChain lagChain;
   private boolean lagChainRunning;
   private long lagChainLastStepNs;
   private float[] lagChainTops = new float[0];
   private int[] lagChainSources = new int[0];
   private final android.view.ViewTreeObserver.OnPreDrawListener lagChainPreDraw = this::stepLagChain;
   private final ExecutorService lyricsParseExecutor;
   private final Handler lyricsMainHandler;
   private boolean tickerEnabled;
   private boolean compactPlaybackClockEnabled;
   private boolean compactSnapshotOnly;
   private boolean playbackRunning;
   private boolean disableBlurForCurrentContent;
   private boolean lyricsDepthBlurEnabled;
   private boolean forceDisableRowBlur;
   private boolean progressScrubbingActive;
   private boolean introPrepEligible;
   private boolean translationVisible;
   private float translationVisibilityProgress;
   private boolean hasTranslationContent;
   private boolean lastDispatchedHasTranslationContent;
   private boolean lastDispatchedTranslationVisible;
   @Nullable
   private OnTranslationStateChangeListener translationStateChangeListener;
   @Nullable
   private OnRowsReadyListener rowsReadyListener;
   @Nullable
   private Runnable lyricsErrorListener;
   @Nullable
   private OnLyricSeekListener lyricSeekListener;
   private long reportedPlaybackPositionMs;
   private long reportedUptimeMs;
   private float reportedPlaybackSpeed;
   private long lyricsParseToken;
   private boolean lyricsParseInFlight;
   @NonNull
   private String lyricsSourceText;
   @Nullable
   private String currentNonLyricHintText;
   private boolean manualScrollAllowedForCurrentContent;
   private int activeTextColor;
   private int inactiveTextColor;
   private boolean darkMode;
   private boolean hdrHighlightEnabled;
   private float hdrWhiteRatio;
   private int contentHeightPx;
   private final int touchSlopPx;
   private final float clickMoveCancelPx;
   private boolean wideAnchorMode;
   /**
    * Settings → lyrics: the sung line rests in the middle instead of near the top, and the lines
    * it has passed stay (fading with distance like the ones ahead) instead of leaving.
    */
   private boolean centerAnchor;
   /** Highest the sung line sits in a wide column, as a share of its height. */
   private float wideAnchorShare;

   /** Which orientation the text sizes below were chosen for. */
   private boolean sizedForPhoneLandscape;
   /** Size, weight and delay chosen in Settings → lyrics; rows are rebuilt when size or weight change. */
   private LyricsStyleStore lyricsStyleStore;
   private final LyricsStyleStore.Listener lyricsStyleListener = store -> this.applyLyricsStyle();
   private android.graphics.Typeface lyricsTypeface;
   private float lyricsSizeScale = 1.0F;
   /** The lyrics run this much behind the song (ahead when negative). */
   private long lyricsDelayMs;
   private boolean lyricsStyleRebuildPosted;
   private boolean lyricsStyleRebuildPending;
   private final Runnable lyricsStyleRebuild = () -> {
      this.lyricsStyleRebuildPosted = false;
      this.rebuildForTextStyle();
   };

   @Override
   protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
      super.onConfigurationChanged(newConfig);
      boolean phoneLandscape = isPhoneLandscape(this.getContext()) || this.compactSizing;
      if (phoneLandscape == this.sizedForPhoneLandscape) return;
      this.sizedForPhoneLandscape = phoneLandscape;
      this.applyTextSizes(phoneLandscape);
      this.rebuildForTextStyle();
   }

   /** The Pad's narrow side panel: the step smaller sizes of a phone turned sideways, always. */
   private boolean compactSizing;

   public void setCompactSizing(boolean compact) {
      if (this.compactSizing == compact) return;
      this.compactSizing = compact;
      boolean sized = isPhoneLandscape(this.getContext()) || compact;
      if (sized == this.sizedForPhoneLandscape) return;
      this.sizedForPhoneLandscape = sized;
      this.applyTextSizes(sized);
      this.rebuildForTextStyle();
   }

   /** The text sizes for this orientation, scaled as chosen in Settings. */
   private void applyTextSizes(boolean phoneLandscape) {
      float scale = this.lyricsSizeScale;
      this.charTextPx = this.sp((phoneLandscape ? 22.0F : 27.0F) * scale);
      this.translationCharTextPx = this.sp((phoneLandscape ? 15.0F : 18.0F) * scale);
      this.wideAnchorShare = phoneLandscape ? 0.20F : 0.30F;
      this.rowHeightPx = Math.max(1, Math.round(this.charTextPx * 1.3F));
      this.translationRowHeightPx = Math.max(1, Math.round(this.translationCharTextPx * 1.25F));
      this.blurViewportExtraPx = (float)(Math.max(this.rowHeightPx, this.translationRowHeightPx) * 2);
      // Between lines: a little more air, except on a phone turned sideways where room is short.
      this.sentenceGapPx = Math.max(0, Math.round(this.dpF(phoneLandscape ? 35.0F : 42.0F)));
      this.wrapMeasurePaint.setTextSize(this.charTextPx);
      this.wrapMeasurePaint.setTypeface(this.lyricsTypeface);
   }

   /** Rows (and pooled ones) carry the old size and font: drop them and lay the lyrics out again. */
   private void rebuildForTextStyle() {
      this.lyricsStyleRebuildPending = false;
      if (this.lyricsStyleRebuildPosted) {
         this.removeCallbacks(this.lyricsStyleRebuild);
         this.lyricsStyleRebuildPosted = false;
      }
      this.recycleRowHoldersToPool();
      this.rowHolders.clear();
      this.mainRowPool.clear();
      this.translationRowPool.clear();
      this.rebuildRows();
      this.requestLayout();
      this.invalidate();
   }

   /** A size or font change that waited while nothing showed: done once something will. */
   private void rebuildForTextStyleIfPending(boolean showing) {
      if (!this.lyricsStyleRebuildPending || !showing || !this.isAttachedToWindow()) return;
      this.lyricsStyleRebuildPending = false;
      this.rebuildForTextStyle();
   }

   /** Takes up what Settings → lyrics says now: the delay at once, size and weight by a rebuild. */
   private void applyLyricsStyle() {
      LyricsStyleStore store = this.lyricsStyleStore;
      if (store == null) return;
      if (this.lagChain != null) this.lagChain.setGain(store.getPullScale());
      this.setCenterAnchor(store.isCenterAnchor());
      long delayMs = store.getDelayMs();
      if (delayMs != this.lyricsDelayMs) {
         // The clock the lines follow moves by the difference, at once.
         long now = SystemClock.uptimeMillis();
         long shown = this.resolveRenderPositionMs(now);
         this.reportedPlaybackPositionMs = Math.max(0L, shown + this.lyricsDelayMs - delayMs);
         this.reportedUptimeMs = now;
         this.lyricsDelayMs = delayMs;
         this.clearTimedVisualProgressState();
         if (this.tickerEnabled && !this.manualScrollActive) {
            this.resolveAndApplyActiveSource(this.progressScrubbingActive);
            this.scheduleTimedLyricsFrameLoopIfNeeded();
         }
      }
      android.graphics.Typeface typeface = store.getTypeface();
      float scale = store.getSizeScale();
      if (typeface != this.lyricsTypeface || Math.abs(scale - this.lyricsSizeScale) > 0.001F) {
         this.lyricsTypeface = typeface;
         this.lyricsSizeScale = scale;
         this.applyTextSizes(this.sizedForPhoneLandscape);
         if (!this.tickerEnabled && !this.compactPlaybackClockEnabled) {
            // Nothing of the lyrics is showing (Settings is in front): the rebuild waits until
            // they are, instead of costing a hitch now.
            this.lyricsStyleRebuildPending = true;
         } else if (!this.lyricsStyleRebuildPosted) {
            this.lyricsStyleRebuildPosted = true;
            this.postOnAnimation(this.lyricsStyleRebuild);
         }
      }
   }

   private static boolean isPhoneLandscape(@NonNull Context context) {
      android.content.res.Configuration configuration = context.getResources().getConfiguration();
      return configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            && configuration.smallestScreenWidthDp > 0
            && configuration.smallestScreenWidthDp < 600;
   }
   private boolean manualScrollActive;
   private boolean manualScrollDragging;
   private boolean manualScrollFlinging;
   private boolean manualScrollNoBlur;
   private boolean manualScrollResumePending;
   /** A finger is on the lyrics: browsing does not end while it stays there. */
   private boolean manualTouchDown;
   private int manualPressedSourceIndex;
   private int pressedFeedbackSourceIndex;
   private float pressedFeedbackProgress;
   private float manualDownX;
   private float manualDownY;
   private float manualLastY;
   private float manualDownTrackY;
   private final OverScroller manualScrollScroller;
   @Nullable
   private VelocityTracker manualVelocityTracker;
   private final Runnable manualScrollFlingRunnable;
   @Nullable
   private ValueAnimator pressedFeedbackAnimator;
   private final Runnable manualScrollResumeRunnable;
   private final int minFlingVelocityPx;
   private final int maxFlingVelocityPx;
   private int activeSourceIndex;
   private int visualSourceIndex;
   private int rowLagAnimToken;
   private boolean sourceAlphaTransitionActive;
   private int sourceAlphaTransitionFrom;
   private int sourceAlphaTransitionTo;
   private float sourceAlphaTransitionProgress;
   private float sourceAlphaTransitionOutgoingStart;
   private float sourceAlphaTransitionIncomingStart;
   private int rowHeightPx;
   private int translationRowHeightPx;
   private int sentenceGapPx;
   private final int wrapRowGapPx;
   private final int translationWrapRowGapPx;
   private final int translationTopGapPx;
   private float charTextPx;
   private float translationCharTextPx;
   private final float rowRightReservePx;
   private final int trackEdgeSpacerPx;
   private final float rowBlurStepPx;
   private final float rowBlurMaxPx;
   private final int rowBlurBoundsOverflowPx;
   private float blurViewportExtraPx;
   private final float blurRadiusQuantizePx;
   private final float mainWeightStrengthPx;
   private final float translationWeightStrengthPx;
   private final float glyphEdgePaddingPx;
   private final float introPrepDotRestBlurPx;
   private final float edgeFadeTopPx;
   private final float edgeFadeBottomPx;
   private final float nonLyricHintTopOffsetPx;
   private final float touchPressCardHorizontalPadPx;
   private final float touchPressCardContentMarginPx;
   private final float touchPressCardTopPadPx;
   private final float touchPressCardBottomPadPx;
   private final float touchPressCardCornerPx;
   private final float touchPressRowSinkPx;
   private final float karaokeGlyphRestOffsetPx;
   private final float karaokeGlyphLongNoteExtraLiftPx;
   private final float karaokeGlyphLongNoteBouncePx;
   private final float karaokeGlyphLongNoteGlowInnerRadiusPx;
   private final float karaokeGlyphLongNoteGlowOuterRadiusPx;
   private final TextPaint wrapMeasurePaint;
   private int lastWrapWidthPx;
   private int lastRowsMeasureWidthPx;
   private boolean timedLyricsFrameLoopActive;
   private boolean timedLyricsFrameDispatching;
   private long timedLyricsFrameDueUptimeMs;
   private final Runnable timedLyricsFrameRunnable;
   private float presentationProgress = 1.0F;
   private float presentationTarget = 1.0F;
   @Nullable
   private ValueAnimator presentationAnimator;
   @Nullable
   private Runnable presentationEndAction;
   private float presentationRisePx;
   @Nullable
   private ValueAnimator trackAnimator;
   /** Hands the highlight to the next sung line while the lyrics are browsed (track left alone). */
   @Nullable
   private ValueAnimator browseHandoffAnimator;
   private int browseHandoffTargetSource = -1;
   private float trackScrollVelocityPxPerMs;
   private float trackScrollLastSampleY;
   private long trackScrollLastSampleUptimeMs;
   @Nullable
   private ValueAnimator visualHandoffCancelledTrackAnimator;
   @Nullable
   private LagSession activeLagSession;
   @Nullable
   private ValueAnimator translationToggleAnimator;
   private int translationToggleToken;
   @NonNull
   private int[] translationIndexByHolder;
   @NonNull
   private int[] translationSourceByIndex;
   @NonNull
   private float[] translationRowProgress;
   private long appliedFocusGeometrySignature;
   private long translationGeometryVersion;
   private int geometryRowHeight = -1, geometryWrapGap = -1;
   private int geometryTranslationHeight = -1, geometryTranslationTopGap = -1;
   private int geometryTranslationWrapGap = -1, geometrySentenceGap = -1;
   private final Interpolator translationToggleInterpolator;
   private final Interpolator trackScrollInterpolator;
   private final long karaokeScrollCurveAdvanceMs;

   public NowPlayingLyricsTickerView(@NonNull Context context) {
      this(context, (AttributeSet)null);
   }

   public NowPlayingLyricsTickerView(@NonNull Context context, @Nullable AttributeSet attrs) {
      this(context, attrs, 0);
   }

   public NowPlayingLyricsTickerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
      super(context, attrs, defStyleAttr);
      this.timedLines = new ArrayList<>();
      this.timedNonLyricLines = new ArrayList<>();
      this.timedTranslations = new ArrayList<>();
      this.timedTranslationTimelines = new ArrayList<>();
      this.plainLines = new ArrayList<>();
      this.plainTranslations = new ArrayList<>();
      this.rowHolders = new ArrayList<>();
      this.sourceGroups = new ArrayList<>();
      this.prepSourceGroups = new ArrayList<>();
      this.mainRowPool = new ArrayList<>();
      this.translationRowPool = new ArrayList<>();
      this.lagTrackedRows = new ArrayList<>();
      this.lyricsParseExecutor = createLyricsParseExecutor();
      this.lyricsMainHandler = new Handler(Looper.getMainLooper());
      this.tickerEnabled = false;
      this.compactPlaybackClockEnabled = false;
      this.compactSnapshotOnly = false;
      this.playbackRunning = false;
      this.disableBlurForCurrentContent = false;
      this.lyricsDepthBlurEnabled = true;
      this.forceDisableRowBlur = false;
      this.progressScrubbingActive = false;
      this.syncPassedReveal();
      this.introPrepEligible = false;
      this.translationVisible = true;
      this.translationVisibilityProgress = 1.0F;
      this.hasTranslationContent = false;
      this.lastDispatchedHasTranslationContent = false;
      this.lastDispatchedTranslationVisible = true;
      this.reportedPlaybackPositionMs = 0L;
      this.reportedUptimeMs = 0L;
      this.reportedPlaybackSpeed = 1.0F;
      this.lyricsParseToken = 0L;
      this.lyricsParseInFlight = false;
      this.lyricsSourceText = "";
      this.currentNonLyricHintText = null;
      this.manualScrollAllowedForCurrentContent = false;
      this.darkMode = false;
      this.hdrHighlightEnabled = false;
      this.hdrWhiteRatio = 1.0F;
      this.contentHeightPx = 1;
      this.wideAnchorMode = false;
      this.manualScrollActive = false;
      this.syncPassedReveal();
      this.manualScrollDragging = false;
      this.manualScrollFlinging = false;
      this.manualScrollNoBlur = false;
      this.manualScrollResumePending = false;
      this.manualPressedSourceIndex = -1;
      this.pressedFeedbackSourceIndex = -1;
      this.pressedFeedbackProgress = 0.0F;
      this.manualDownX = 0.0F;
      this.manualDownY = 0.0F;
      this.manualLastY = 0.0F;
      this.manualDownTrackY = 0.0F;
      this.manualVelocityTracker = null;
      this.manualScrollFlingRunnable = new Runnable() {
         public void run() {
            NowPlayingLyricsTickerView.this.runManualScrollFlingFrame();
         }
      };
      this.manualScrollResumeRunnable = new Runnable() {
         public void run() {
            NowPlayingLyricsTickerView.this.manualScrollResumePending = false;
            NowPlayingLyricsTickerView.this.finishManualScrollPause();
         }
      };
      this.activeSourceIndex = -1;
      this.visualSourceIndex = -1;
      this.rowLagAnimToken = 0;
      this.sourceAlphaTransitionActive = false;
      this.sourceAlphaTransitionFrom = -1;
      this.sourceAlphaTransitionTo = -1;
      this.sourceAlphaTransitionProgress = 1.0F;
      this.sourceAlphaTransitionOutgoingStart = 1.0F;
      this.sourceAlphaTransitionIncomingStart = 0.6F;
      this.trackScrollVelocityPxPerMs = 0.0F;
      this.trackScrollLastSampleY = Float.NaN;
      this.trackScrollLastSampleUptimeMs = Long.MIN_VALUE;
      this.wrapMeasurePaint = com.lab.hv.util.AppFont.track(new TextPaint(1));
      this.lastWrapWidthPx = -1;
      this.lastRowsMeasureWidthPx = -1;
      this.timedLyricsFrameLoopActive = false;
      this.timedLyricsFrameDispatching = false;
      this.timedLyricsFrameDueUptimeMs = Long.MAX_VALUE;
      this.timedLyricsFrameRunnable = new Runnable() {
         public void run() {
            NowPlayingLyricsTickerView.this.timedLyricsFrameLoopActive = false;
            NowPlayingLyricsTickerView.this.timedLyricsFrameDueUptimeMs = Long.MAX_VALUE;
            if (NowPlayingLyricsTickerView.this.shouldScheduleTimedLyricsFrame()) {
               NowPlayingLyricsTickerView.this.timedLyricsFrameDispatching = true;

               try {
                  NowPlayingLyricsTickerView.this.resolveAndApplyActiveSource(false);
               } finally {
                  NowPlayingLyricsTickerView.this.timedLyricsFrameDispatching = false;
               }

               // No invalidate of this view here: every row, glyph and track change above
               // invalidates the view it changed. Invalidating the whole ticker damaged its full
               // bounds each frame, so every blurred row re-ran its blur at the display rate even
               // when only one karaoke glyph moved.
               NowPlayingLyricsTickerView.this.scheduleTimedLyricsFrameLoopIfNeeded();
            }
         }
      };
      this.translationToggleToken = 0;
      this.translationIndexByHolder = new int[0];
      this.translationSourceByIndex = new int[0];
      this.translationRowProgress = new float[0];
      this.appliedFocusGeometrySignature = Long.MIN_VALUE;
      this.translationToggleInterpolator = BezierControl.getBezierInterpolator(1);
      this.trackScrollInterpolator = BezierControl.getBezierInterpolator(7);
      this.karaokeScrollCurveAdvanceMs = this.resolveCurveSyncedScrollAdvanceMs();
      ViewConfiguration configuration = ViewConfiguration.get(context);
      this.touchSlopPx = configuration.getScaledTouchSlop();
      this.minFlingVelocityPx = configuration.getScaledMinimumFlingVelocity();
      this.maxFlingVelocityPx = configuration.getScaledMaximumFlingVelocity();
      this.manualScrollScroller = new OverScroller(context);
      this.clickMoveCancelPx = this.dpF(6.0F);
      this.setClickable(true);
      this.setClipChildren(true);
      this.setClipToPadding(true);
      // A phone turned sideways: a step smaller, so more lines fit. Rotation no longer rebuilds
      // this view, so onConfigurationChanged re-applies these when the orientation changes.
      boolean phoneLandscape = isPhoneLandscape(context);
      this.sizedForPhoneLandscape = phoneLandscape;
      this.lyricsStyleStore = LyricsStyleStore.get(context);
      this.lyricsTypeface = this.lyricsStyleStore.getTypeface();
      this.lyricsSizeScale = this.lyricsStyleStore.getSizeScale();
      this.lyricsDelayMs = this.lyricsStyleStore.getDelayMs();
      this.centerAnchor = this.lyricsStyleStore.isCenterAnchor();
      this.applyTextSizes(phoneLandscape);
      this.lagChain = new LyricLagChain((float)this.rowHeightPx);
      this.lagChain.setGain(this.lyricsStyleStore.getPullScale());
      this.wrapRowGapPx = Math.max(0, Math.round(this.dpF(1.5F)));
      this.translationWrapRowGapPx = Math.max(0, Math.round(this.dpF(1.3F)));
      this.translationTopGapPx = Math.max(0, Math.round(this.dpF(2.0F)));
      this.rowRightReservePx = this.dpF(25.0F);
      this.trackEdgeSpacerPx = Math.max(0, Math.round(this.dpF(300.0F)));
      this.rowBlurStepPx = this.dpF(1.15F);
      this.rowBlurMaxPx = this.dpF(8.5F);
      this.rowBlurBoundsOverflowPx = Math.max(0, (int)Math.ceil((double)(this.rowBlurMaxPx * ROW_BLUR_BOUNDS_OVERFLOW_RATIO)));
      this.blurViewportExtraPx = (float)(Math.max(this.rowHeightPx, this.translationRowHeightPx) * 2);
      this.blurRadiusQuantizePx = Math.max(0.08F, this.rowBlurStepPx * 0.05F);
      this.mainWeightStrengthPx = this.dpF(0.85F);
      this.translationWeightStrengthPx = this.dpF(0.0F);
      // Covers the maximum long-note lift plus the broad glow kernel. Keeping the blur fully
      // inside each glyph view prevents a rectangular edge from appearing at the view bounds.
      this.glyphEdgePaddingPx = this.dpF(KARAOKE_GLYPH_LONG_NOTE_VISUAL_PADDING_DP);
      this.introPrepDotRestBlurPx = this.dpF(5.0F);
      this.edgeFadeTopPx = this.dpF(26.0F);
      this.edgeFadeBottomPx = this.dpF(50.0F);
      this.nonLyricHintTopOffsetPx = this.dpF(6.0F);
      this.presentationRisePx = this.dpF(PRESENTATION_RISE_DP);
      this.touchPressCardContentMarginPx = this.dpF(3.0F);
      this.touchPressCardHorizontalPadPx = this.dpF(14.0F);
      this.touchPressCardTopPadPx = this.dpF(8.0F);
      this.touchPressCardBottomPadPx = this.dpF(9.0F);
      this.touchPressCardCornerPx = this.dpF(18.0F);
      this.touchPressRowSinkPx = this.dpF(1.8F);
      this.karaokeGlyphRestOffsetPx = this.dpF(KARAOKE_GLYPH_REST_OFFSET_DP);
      this.karaokeGlyphLongNoteExtraLiftPx = this.dpF(KARAOKE_GLYPH_LONG_NOTE_EXTRA_LIFT_DP);
      this.karaokeGlyphLongNoteBouncePx = this.dpF(KARAOKE_GLYPH_LONG_NOTE_BOUNCE_DP);
      this.karaokeGlyphLongNoteGlowInnerRadiusPx = this.dpF(KARAOKE_GLYPH_LONG_NOTE_GLOW_INNER_RADIUS_DP);
      this.karaokeGlyphLongNoteGlowOuterRadiusPx = this.dpF(KARAOKE_GLYPH_LONG_NOTE_GLOW_OUTER_RADIUS_DP);
      SPText.prewarmBlurMaskRadiusPx(this.karaokeGlyphLongNoteGlowInnerRadiusPx);
      SPText.prewarmBlurMaskRadiusPx(this.karaokeGlyphLongNoteGlowOuterRadiusPx);
      this.wrapMeasurePaint.setTextSize(this.charTextPx);
      this.prewarmRowBlurMasks();
      this.trackContainer = new RowTrackLayout(context);
      this.trackContainer.setClipChildren(false);
      this.trackContainer.setClipToPadding(false);
      this.pressedRowCardView = new PressedRowCardView(context, this.touchPressCardCornerPx);
      this.pressedRowCardView.setVisibility(4);
      this.trackContainer.addView(this.pressedRowCardView, new FrameLayout.LayoutParams(0, 0));
      this.addView(this.trackContainer, new FrameLayout.LayoutParams(-1, -2));
      this.nonLyricHintView = new SPText(context);
      this.nonLyricHintView.setTextSizePx(this.sp(14.0F));
      this.nonLyricHintView.setWeightStrengthPx(this.dpF(0.18F));
      this.nonLyricHintView.setEdgePaddingPx(this.dpF(1.4F));
      this.nonLyricHintView.setVisibility(8);
      this.nonLyricHintView.setAlpha(0.0F);
      this.addView(this.nonLyricHintView, new FrameLayout.LayoutParams(-2, -2));
      this.applyDarkMode(false);
   }

   @NonNull
   private static ExecutorService createLyricsParseExecutor() {
      AtomicInteger threadIndex = new AtomicInteger();
      ThreadFactory factory = (r) -> {
         Thread t = new Thread(() -> {
            Process.setThreadPriority(10);
            r.run();
         }, "lyrics-parse-" + threadIndex.incrementAndGet());
         t.setPriority(1);
         return t;
      };
      ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 12L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1), factory, (r, e) -> {
         if (!e.isShutdown()) {
            e.getQueue().poll();

            try {
               e.execute(r);
            } catch (RejectedExecutionException var3) {
            }

         }
      });
      executor.allowCoreThreadTimeOut(true);
      return executor;
   }

   public void applyDarkMode(boolean darkMode) {
      this.darkMode = darkMode;
      this.activeTextColor = darkMode ? -527380 : -15066598;
      this.inactiveTextColor = darkMode ? -1493699604 : -1944446438;
      int hintColor = darkMode ? -1191709716 : -1726342630;
      this.nonLyricHintView.setTextColorInt(hintColor);
      this.pressedRowCardView.setCardColor(darkMode ? this.withColorAlpha(-527380, 0.12F) : this.withColorAlpha(-15066598, 0.07F));
      this.refreshRowColors();
   }

   public void setHdrHighlightEnabled(boolean enabled, float whiteRatio) {
      boolean safeEnabled = enabled && VERSION.SDK_INT >= 29;
      float safeRatio = Math.max(1.0F, Math.min(2000.0F, whiteRatio));
      if (this.hdrHighlightEnabled != safeEnabled || !(Math.abs(this.hdrWhiteRatio - safeRatio) < 0.01F)) {
         this.hdrHighlightEnabled = safeEnabled;
         this.hdrWhiteRatio = safeRatio;
         this.refreshRowColors();
      }
   }

   public void setForceDisableRowBlur(boolean forceDisable) {
      if (this.forceDisableRowBlur != forceDisable) {
         this.forceDisableRowBlur = forceDisable;
         this.refreshRowColors();
      }
   }

   public void setLyricsDepthBlurEnabled(boolean enabled) {
      if (this.lyricsDepthBlurEnabled != enabled) {
         this.lyricsDepthBlurEnabled = enabled;
         this.refreshRowColors();
         if (!enabled) {
            this.refreshRowBlurState();
         }

         this.invalidate();
      }
   }

   public void setProgressScrubbingActive(boolean active) {
      if (this.progressScrubbingActive != active) {
	         this.progressScrubbingActive = active;
	         this.syncPassedReveal();
	         if (this.progressScrubbingActive) {
	            this.finishBrowseHandoff();
	            this.cancelScheduledManualScrollResume();
	            this.stopManualScrollFling();
	            this.stopTrackAnimator();
	            this.clearSourceAlphaTransition();
	            this.clearRowLagAnimations();
	            this.stopTimedLyricsFrameLoop();
	         } else if (this.manualScrollActive && this.playbackRunning) {
	            this.scheduleManualScrollResume();
	         }

         this.reportedUptimeMs = SystemClock.uptimeMillis();
         if (!this.tickerEnabled) {
            long renderPositionMs = this.resolveRenderPositionMs();
            this.refreshRowColors();
            this.refreshRowAlphaState(renderPositionMs);
            this.refreshPrepCountdownRows(renderPositionMs);
         } else {
            this.resolveAndApplyActiveSource(true);
            this.scheduleTimedLyricsFrameLoopIfNeeded();
         }
      }
   }

   /** Folia already parsed its provider's lyrics, including the independently supplied translation. */
   public void setMonetLines(@NonNull List<io.github.techuouo520.folia.bridge.MonetTickerLine> source) {
      long token = ++this.lyricsParseToken;
      this.lyricsParseInFlight = true;
      this.lyricsParseExecutor.execute(() -> {
         try {
            List<TimedLine> lines = new ArrayList<>(source.size());
            List<String> translations = new ArrayList<>(source.size());
            List<KaraokeTimeline> subtitles = new ArrayList<>(source.size());
            for (io.github.techuouo520.folia.bridge.MonetTickerLine line : source) {
               List<Long> times = line.getWordTimes();
               List<Integer> ends = line.getWordEnds();
               KaraokeTimeline timeline = buildKaraokeTimeline(times, ends, line.getText());
               if (times.size() == 1 && ends.size() == 1 && ends.get(0) > 0) {
                  timeline = buildKaraokeTimelineWithTextShape(new long[]{times.get(0)},
                        new int[]{ends.get(0)}, line.getText().codePoints().toArray());
               }
               if (timeline != null) timeline.terminalEndMs = line.getEndMs();
               lines.add(new TimedLine(line.getStartMs(), line.getText(), false, -1L, timeline));
               String translation = line.getTranslation();
               translations.add(translation);
               subtitles.add(timeline == null || TextUtils.isEmpty(translation) ? null
                     : buildMappedTranslationKaraokeTimeline(timeline, translation));
            }
            ParsedLyricsData parsed = new ParsedLyricsData(lines, Collections.emptyList(), translations,
                  subtitles, Collections.emptyList(), Collections.emptyList());
            this.lyricsMainHandler.post(() -> {
               if (this.lyricsParseToken != token) return;
               this.lyricsParseInFlight = false;
               this.disableBlurForCurrentContent = lines.isEmpty();
               try {
                  this.applyParsedLyricsData(parsed);
               } catch (RuntimeException error) {
                  this.failMonetLines(token, error);
               }
            });
         } catch (RuntimeException error) {
            this.lyricsMainHandler.post(() -> this.failMonetLines(token, error));
         }
      });
   }

   private void failMonetLines(long token, RuntimeException error) {
      if (this.lyricsParseToken != token) return;
      this.lyricsParseInFlight = false;
      ++this.rowBuildGeneration;
      this.rowBuildActive = false;
      this.rowBuildOnPrepared = null;
      android.view.Choreographer.getInstance().removeFrameCallback(this.rowBuildFrame);
      android.util.Log.w("FoliaLyrics", "Could not prepare lyric rows", error);
      if (this.lyricsErrorListener != null) this.lyricsErrorListener.run();
   }

   /** Use the font sizes of the poster layout, in physical pixels, including its size setting. */
   public void setMonetTextSize(float mainPx, float subtitlePx) {
      if (mainPx <= 0 || subtitlePx <= 0 ||
            (Math.abs(mainPx - charTextPx) < .01f && Math.abs(subtitlePx - translationCharTextPx) < .01f)) return;
      charTextPx = mainPx;
      translationCharTextPx = subtitlePx;
      rowHeightPx = Math.max(1, Math.round(mainPx * 1.3f));
      translationRowHeightPx = Math.max(1, Math.round(subtitlePx * 1.25f));
      blurViewportExtraPx = Math.max(rowHeightPx, translationRowHeightPx) * 2f;
      wrapMeasurePaint.setTextSize(mainPx);
      if (!timedLines.isEmpty()) rebuildForTextStyle();
   }

   public void setLyricsText(@Nullable String text) {
      String source = text == null ? "" : text;
      if (!TextUtils.equals(source, this.lyricsSourceText)) {
         this.lyricsSourceText = source;
         long token = ++this.lyricsParseToken;
         this.lyricsParseInFlight = true;
         this.lyricsParseExecutor.execute(() -> {
            ParsedLyricsData parsed = this.parseLyricsTextInternal(source);
            boolean disableBlur = this.shouldDisableBlurForContent(source, parsed);
            this.lyricsMainHandler.post(() -> {
               if (this.lyricsParseToken == token) {
                  this.lyricsParseInFlight = false;
                  this.disableBlurForCurrentContent = disableBlur;
                  this.applyParsedLyricsData(parsed);
               }
            });
         });
      }
   }

   @Nullable
   public CompactLyricSnapshot getCompactLyricSnapshot() {
      if (this.timedLines.isEmpty()) {
         for (int i = 0; i < this.plainLines.size(); i++) {
            String text = (String)this.plainLines.get(i);
            if (!TextUtils.isEmpty(text)) {
               int count = text.codePointCount(0, text.length());
               return new CompactLyricSnapshot(text, count, false);
            }
         }
         return null;
      }
      long positionMs = this.resolveRenderPositionMs();
      int low = 0;
      int high = this.timedLines.size() - 1;
      int sourceIndex = -1;
      while (low <= high) {
         int middle = (low + high) >>> 1;
         TimedLine middleLine = (TimedLine)this.timedLines.get(middle);
         if (middleLine.timeMs <= positionMs) {
            sourceIndex = middle;
            low = middle + 1;
         } else {
            high = middle - 1;
         }
      }
      while (sourceIndex >= 0
            && ((TimedLine)this.timedLines.get(sourceIndex)).prepCountdown) {
         --sourceIndex;
      }
      if (sourceIndex < 0) {
         for (int i = 0; i < this.timedLines.size(); i++) {
            TimedLine line = (TimedLine)this.timedLines.get(i);
            if (!line.prepCountdown) {
               sourceIndex = i;
               break;
            }
         }
      }
      if (sourceIndex < 0 || sourceIndex >= this.timedLines.size()) {
         return null;
      }
      TimedLine line = (TimedLine)this.timedLines.get(sourceIndex);
      if (line.prepCountdown || TextUtils.isEmpty(line.text)) {
         return null;
      }
      KaraokeTimeline timeline = line.karaokeTimeline;
      int lineCodePointCount = line.text.codePointCount(0, line.text.length());
      if (timeline == null) {
         return new CompactLyricSnapshot(line.text, lineCodePointCount, false);
      }
      return new CompactLyricSnapshot(
            line.text,
            this.resolveCompactKaraokeRevealCodePoints(timeline, positionMs, lineCodePointCount),
            true
      );
   }

   public void setCompactSnapshotOnly(boolean enabled) {
      if (this.compactSnapshotOnly == enabled) return;
      if (!this.rowHolders.isEmpty() || !this.sourceGroups.isEmpty()) {
         throw new IllegalStateException("Compact snapshot mode must be selected before lyrics are parsed");
      }
      this.compactSnapshotOnly = enabled;
   }

   @NonNull
   public List<String> getCompactLyricTexts() {
      ArrayList<String> result = new ArrayList<>();
      if (!this.timedLines.isEmpty()) {
         for (int i = 0; i < this.timedLines.size(); i++) {
            TimedLine line = (TimedLine)this.timedLines.get(i);
            if (!line.prepCountdown && !TextUtils.isEmpty(line.text)) result.add(line.text);
         }
      } else {
         for (int i = 0; i < this.plainLines.size(); i++) {
            String line = (String)this.plainLines.get(i);
            if (!TextUtils.isEmpty(line)) result.add(line);
         }
      }
      return Collections.unmodifiableList(result);
   }

   private float resolveCompactKaraokeRevealCodePoints(@NonNull KaraokeTimeline timeline,
                                                        long positionMs,
                                                        int lineCodePointCount) {
      int tokenCount = Math.min(
            Math.min(timeline.tokenTimeMs.length, timeline.tokenStartCodePoint.length),
            Math.min(timeline.tokenEndCodePoint.length, timeline.tokenVisibleCodePointCount.length)
      );
      if (tokenCount <= 0 || positionMs < timeline.tokenTimeMs[0]) {
         return 0.0F;
      }
      int tokenIndex = 0;
      for (int i = 1; i < tokenCount; i++) {
         if (positionMs < timeline.tokenTimeMs[i]) break;
         tokenIndex = i;
      }
      int start = Math.max(0, Math.min(lineCodePointCount, timeline.tokenStartCodePoint[tokenIndex]));
      int end = Math.max(start, Math.min(lineCodePointCount, timeline.tokenEndCodePoint[tokenIndex]));
      int visibleCount = Math.max(0, timeline.tokenVisibleCodePointCount[tokenIndex]);
      if (visibleCount <= 0) {
         return end;
      }
      long durationMs = Math.max(1L, this.resolveKaraokeTokenDurationMs(timeline, tokenIndex, tokenCount));
      float local = Math.max(0.0F, Math.min(1.0F,
            (float)(positionMs - timeline.tokenTimeMs[tokenIndex]) / durationMs));
      float visibleProgress = local * visibleCount;
      if (visibleProgress >= visibleCount) return end;
      int targetOrdinal = Math.max(0, Math.min(visibleCount - 1, (int)Math.floor(visibleProgress)));
      float targetLocal = Math.max(0.0F, Math.min(1.0F, visibleProgress - targetOrdinal));
      for (int codePoint = start; codePoint < end; codePoint++) {
         if (timeline.resolveVisibleOrdinalInToken(codePoint) == targetOrdinal) {
            return Math.min(lineCodePointCount, codePoint + targetLocal);
         }
      }
      return start;
   }

   public void cancelPendingLyricsWork() {
      if (this.lyricsParseInFlight) {
         ++this.lyricsParseToken;
         this.lyricsParseInFlight = false;
         this.lyricsSourceText = "";
      }
   }

   public void prewarmLyricsText(@Nullable String text) {
      String source = text == null ? "" : text;
      if (!TextUtils.isEmpty(source) && !TextUtils.equals(source, this.lyricsSourceText)) {
         long token = ++this.lyricsParseToken;
         this.lyricsParseInFlight = true;
         this.lyricsSourceText = source;
         this.lyricsParseExecutor.execute(() -> {
            ParsedLyricsData parsed = this.parseLyricsTextInternal(source);
            boolean disableBlur = this.shouldDisableBlurForContent(source, parsed);
            this.lyricsMainHandler.post(() -> {
               if (this.lyricsParseToken == token) {
                  this.lyricsParseInFlight = false;
                  this.disableBlurForCurrentContent = disableBlur;
                  this.applyParsedLyricsData(parsed);
               }
            });
         });
      }
   }

   /** The current lyrics' timed lines, for the 3D lyric stage. */
   @NonNull
   public List<LyricStageLine> getStageLines() {
      ArrayList<LyricStageLine> out = new ArrayList<>();
      ParsedLyricsData parsed = this.parsedForStage;
      if (parsed == null) return out;
      for (int i = 0; i < parsed.timedLines.size(); ++i) {
         TimedLine line = (TimedLine)parsed.timedLines.get(i);
         if (line.prepCountdown || TextUtils.isEmpty(line.text)) continue;
         KaraokeTimeline timeline = line.karaokeTimeline;
         out.add(new LyricStageLine(
               line.timeMs,
               line.text,
               timeline == null ? null : timeline.tokenTimeMs,
               timeline == null ? null : timeline.tokenStartCodePoint,
               timeline == null ? null : timeline.tokenEndCodePoint,
               timeline == null ? -1L : timeline.terminalEndMs));
      }
      return out;
   }

   /** The translation of each of {@link #getStageLines()}'s lines, in the same order (null for none). */
   public List<String> getStageTranslations() {
      ArrayList<String> out = new ArrayList<>();
      ParsedLyricsData parsed = this.parsedForStage;
      if (parsed == null) return out;
      for (int i = 0; i < parsed.timedLines.size(); ++i) {
         TimedLine line = (TimedLine)parsed.timedLines.get(i);
         if (line.prepCountdown || TextUtils.isEmpty(line.text)) continue;
         out.add(i < parsed.timedTranslations.size() ? (String)parsed.timedTranslations.get(i) : null);
      }
      return out;
   }

   /** Told on the main thread each time new lyrics have been parsed. */
   public void setStageLinesListener(@Nullable Runnable listener) {
      this.stageLinesListener = listener;
   }

   @Nullable
   private ParsedLyricsData parsedForStage;
   @Nullable
   private Runnable stageLinesListener;

   private void applyParsedLyricsData(@NonNull ParsedLyricsData parsed) {
      this.parsedForStage = parsed;
      if (this.stageLinesListener != null) this.stageLinesListener.run();
      this.timedLines.clear();
      this.timedNonLyricLines.clear();
      this.timedTranslations.clear();
      this.timedTranslationTimelines.clear();
      this.plainLines.clear();
      this.plainTranslations.clear();
      this.hideNonLyricHintView();
      this.manualScrollAllowedForCurrentContent = this.shouldAllowManualScrollForParsedContent(this.lyricsSourceText, parsed);
      if (!parsed.timedLines.isEmpty()) {
         long previousStampMs = 0L;

         for(int i = 0; i < parsed.timedLines.size(); ++i) {
            TimedLine line = (TimedLine)parsed.timedLines.get(i);
            String translation = i < parsed.timedTranslations.size() ? (String)parsed.timedTranslations.get(i) : null;
            long gapMs = line.timeMs - previousStampMs;
            long threshold = previousStampMs <= 0L ? 10000L : 25000L;
            if (gapMs >= threshold) {
               long prepStartMs;
               if (previousStampMs <= 0L) {
                  prepStartMs = 0L;
               } else {
                  long dynamicLeadMs = Math.max(4000L, gapMs / 2L);
                  dynamicLeadMs = Math.min(dynamicLeadMs, 12000L);
                  prepStartMs = Math.max(previousStampMs + 1L, line.timeMs - dynamicLeadMs);
               }

               this.timedLines.add(new TimedLine(prepStartMs, "●●●", true, line.timeMs));
               this.timedTranslations.add((String)null);
               this.timedTranslationTimelines.add((KaraokeTimeline)null);
            }

            this.timedLines.add(new TimedLine(line.timeMs, line.text, false, -1L, line.karaokeTimeline));
            this.timedTranslations.add(translation);
            this.timedTranslationTimelines.add(i < parsed.timedTranslationTimelines.size() ? (KaraokeTimeline)parsed.timedTranslationTimelines.get(i) : null);
            previousStampMs = line.timeMs;
         }

         // A held last note ends before whatever is shown next (the next line or its countdown).
         for(int i = 0; i + 1 < this.timedLines.size(); ++i) {
            long nextStartMs = ((TimedLine)this.timedLines.get(i + 1)).timeMs;
            this.clampTerminalEnd(((TimedLine)this.timedLines.get(i)).karaokeTimeline, nextStartMs);
            this.clampTerminalEnd((KaraokeTimeline)this.timedTranslationTimelines.get(i), nextStartMs);
         }
      }

      this.timedNonLyricLines.addAll(parsed.timedNonLyricLines);
      this.plainLines.addAll(parsed.plainLines);
      this.plainTranslations.addAll(parsed.plainTranslations);
      this.hasTranslationContent = this.containsNonEmptyTranslation(parsed.timedTranslations) || this.containsNonEmptyTranslation(parsed.plainTranslations);
      this.updateIntroPrepEligibility();
      if (this.compactSnapshotOnly) {
         this.activeSourceIndex = -1;
         this.visualSourceIndex = -1;
         this.dispatchTranslationStateChangedIfNeeded();
         this.dispatchRowsReady();
         return;
      }
      this.rebuildRows();
      this.activeSourceIndex = -1;
      this.visualSourceIndex = -1;
      if (this.rowBuildActive) {
         this.dispatchTranslationStateChangedIfNeeded();
         return;
      }
      this.resolveAndApplyActiveSource(true);
      this.scheduleTimedLyricsFrameLoopIfNeeded();
      this.dispatchTranslationStateChangedIfNeeded();
      this.dispatchRowsReady();
      this.requestLayout();
      this.postInvalidateOnAnimation();
   }

   private void dispatchRowsReady() {
      if (this.rowsReadyListener != null) {
         this.rowsReadyListener.onRowsReady();
      }

   }

   private boolean containsNonEmptyTranslation(@NonNull List<String> translations) {
      for(int i = 0; i < translations.size(); ++i) {
         if (!TextUtils.isEmpty((CharSequence)translations.get(i))) {
            return true;
         }
      }

      return false;
   }

   private void updateIntroPrepEligibility() {
      boolean eligible = false;

      for(int i = 0; i < this.timedLines.size(); ++i) {
         if (((TimedLine)this.timedLines.get(i)).prepCountdown) {
            eligible = true;
            break;
         }
      }

      if (this.introPrepEligible != eligible) {
         this.introPrepEligible = eligible;
         if (this.introPrepEligible && this.tickerEnabled) {
            this.refreshPrepCountdownRows(this.resolveRenderPositionMs());
            this.scheduleTimedLyricsFrameLoopIfNeeded();
         }

         this.invalidate();
      }
   }

   @NonNull
   private ParsedLyricsData parseLyricsTextInternal(@Nullable String text) {
      ArrayList<TimedLine> parsedTimedLines = new ArrayList<>();
      ArrayList<TimedLine> parsedTimedNonLyricLines = new ArrayList<>();
      ArrayList<String> parsedTimedTranslations = new ArrayList<>();
      ArrayList<KaraokeTimeline> parsedTimedTranslationTimelines = new ArrayList<>();
      ArrayList<String> parsedPlainLines = new ArrayList<>();
      ArrayList<String> parsedPlainTranslations = new ArrayList<>();
      if (TextUtils.isEmpty(text)) {
         return new ParsedLyricsData(parsedTimedLines, parsedTimedNonLyricLines, parsedTimedTranslations, parsedTimedTranslationTimelines, parsedPlainLines, parsedPlainTranslations);
      } else {
         String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
         String[] rawLines = normalized.split("\n");
         ArrayList<TimedTextToken> timedTokens = new ArrayList<>();
         int emittedOrder = 0;
         String pendingUntimedPerformerMarker = null;

         for(String rawLine : rawLines) {
            if (!TextUtils.isEmpty(rawLine)) {
               boolean containsTimedTag = LRC_TIME_PATTERN.matcher(rawLine).find();
               ArrayList<TimedTextToken> lineTokens = this.parseTimedTokensForRawLine(rawLine, emittedOrder);
               if (!lineTokens.isEmpty()) {
                  TimedTextToken firstLyricToken = null;
                  boolean hasExplicitTimedPerformerMarker = false;
                  for(TimedTextToken token : lineTokens) {
                     if (!TextUtils.isEmpty(token.performerMarker)) {
                        hasExplicitTimedPerformerMarker = true;
                     } else if (!token.nonLyric && firstLyricToken == null) {
                        firstLyricToken = token;
                     }
                  }

                  if (hasExplicitTimedPerformerMarker) {
                     // An explicitly timed declaration is authoritative and replaces any
                     // un-timed declaration waiting to be attached to a following lyric.
                     pendingUntimedPerformerMarker = null;
                  } else if (!TextUtils.isEmpty(pendingUntimedPerformerMarker)
                        && firstLyricToken != null) {
                     timedTokens.add(new TimedTextToken(
                           firstLyricToken.timeMs,
                           emittedOrder++,
                           pendingUntimedPerformerMarker,
                           false,
                           (KaraokeTimeline)null,
                           pendingUntimedPerformerMarker
                     ));
                     pendingUntimedPerformerMarker = null;
                  }
                  timedTokens.addAll(lineTokens);
                  emittedOrder += lineTokens.size();
               } else if (!containsTimedTag) {
                  String plainText = this.normalizeLyricLineText(this.collapseSpaces(rawLine));
                  String performerMarker = this.extractPerformerMarkerLabel(plainText);
                  if (performerMarker != null) {
                     // Some providers omit a timestamp on a performer-only row. In timed
                     // lyrics, treat it as state for the next actual lyric instead of
                     // rendering the declaration as a scrolling row.
                     pendingUntimedPerformerMarker = performerMarker;
                  } else if (!this.shouldSkipLyricLineText(plainText)
                        && !this.isLikelyNonLyricLine(plainText)) {
                     parsedPlainLines.add(plainText);
                     parsedPlainTranslations.add((String)null);
                  }
               }
            }
         }

         if (timedTokens.isEmpty()) {
            return new ParsedLyricsData(parsedTimedLines, parsedTimedNonLyricLines, parsedTimedTranslations, parsedTimedTranslationTimelines, parsedPlainLines, parsedPlainTranslations);
         } else {
            timedTokens.sort((a, b) -> a.timeMs != b.timeMs ? Long.compare(a.timeMs, b.timeMs) : Integer.compare(a.order, b.order));
            ArrayList<TimedTextToken> lyricTokens = new ArrayList<>(timedTokens.size());

            int j;
            for(int i = 0; i < timedTokens.size(); i = j) {
               long time = ((TimedTextToken)timedTokens.get(i)).timeMs;
               ArrayList<String> nonLyricChunk = new ArrayList<>();

               for(j = i; j < timedTokens.size() && ((TimedTextToken)timedTokens.get(j)).timeMs == time; ++j) {
                  TimedTextToken token = (TimedTextToken)timedTokens.get(j);
                  if (!TextUtils.isEmpty(token.performerMarker)) {
                     // A singer declaration ("周杰伦：") is not a line of the lyrics.
                     continue;
                  } else if (token.nonLyric) {
                     String hint = this.normalizeNonLyricHintText(token.text);
                     if (!TextUtils.isEmpty(hint)) {
                        nonLyricChunk.add(hint);
                     }
                  } else {
                     lyricTokens.add(token);
                  }
               }

               if (!nonLyricChunk.isEmpty()) {
                  StringBuilder builder = new StringBuilder((String)nonLyricChunk.get(0));

                  for(int k = 1; k < nonLyricChunk.size(); ++k) {
                     if (builder.length() > 0) {
                        builder.append(" · ");
                     }

                     builder.append((String)nonLyricChunk.get(k));
                  }

                  String hintLine = builder.toString().trim();
                  if (!TextUtils.isEmpty(hintLine)) {
                     parsedTimedNonLyricLines.add(new TimedLine(time, "· " + hintLine));
                  }
               }
            }

            java.util.IdentityHashMap<TimedTextToken, TimedTextToken> attachedTranslations = this.attachMixedFormatTranslations(lyricTokens);
            int cursor = 0;

            while(cursor < lyricTokens.size()) {
               TimedTextToken primaryToken = (TimedTextToken)lyricTokens.get(cursor);
               ++cursor;
               String translation = null;
               KaraokeTimeline translationTimeline = null;

               int translationSegments = 0;
               TimedTextToken attachedTranslation = attachedTranslations.get(primaryToken);
               if (attachedTranslation != null) {
                  translation = attachedTranslation.text;
                  translationSegments = 1;
               }
               for(; attachedTranslation == null && cursor < lyricTokens.size() && this.isTranslationPairCandidate(primaryToken, (TimedTextToken)lyricTokens.get(cursor)); ++translationSegments) {
                  TimedTextToken candidate = (TimedTextToken)lyricTokens.get(cursor);
                  ++cursor;
                  if (translation == null) {
                     translation = candidate.text;
                     translationTimeline = candidate.karaokeTimeline;
                  } else if (!TextUtils.isEmpty(candidate.text)) {
                     translation = translation + " / " + candidate.text;
                  }
               }

               if (TextUtils.isEmpty(translation)) {
                  translation = null;
                  translationTimeline = null;
               } else {
                  if (translationSegments > 1) {
                     translationTimeline = null;
                  }

                  if (translationTimeline == null && primaryToken.karaokeTimeline != null) {
                     translationTimeline = this.buildMappedTranslationKaraokeTimeline(primaryToken.karaokeTimeline, translation);
                  }
               }

               parsedTimedLines.add(new TimedLine(primaryToken.timeMs, primaryToken.text, false, -1L, primaryToken.karaokeTimeline));
               parsedTimedTranslations.add(translation);
               parsedTimedTranslationTimelines.add(translationTimeline);
            }

            return new ParsedLyricsData(parsedTimedLines, parsedTimedNonLyricLines, parsedTimedTranslations, parsedTimedTranslationTimelines, parsedPlainLines, parsedPlainTranslations);
         }
      }
   }

   /**
    * Mixed-format lyrics: word-by-word lines, each followed in the file by a line-timed translation
    * whose timestamp comes from another clock (NetEase yrc lines start at 01.68 while their
    * translation says 02.02), so the two seldom share a time. A line-timed line straight after a
    * word-by-word one, starting from a moment before it up to its last word, is its translation.
    * The translations found are taken out of {@code lyricTokens}; the map gives each one's line.
    */
   @NonNull
   private java.util.IdentityHashMap<TimedTextToken, TimedTextToken> attachMixedFormatTranslations(@NonNull ArrayList<TimedTextToken> lyricTokens) {
      java.util.IdentityHashMap<TimedTextToken, TimedTextToken> attached = new java.util.IdentityHashMap<>();
      android.util.SparseArray<TimedTextToken> byOrder = new android.util.SparseArray<>(lyricTokens.size());
      for (TimedTextToken token : lyricTokens) {
         byOrder.put(token.order, token);
      }
      java.util.IdentityHashMap<TimedTextToken, Boolean> taken = new java.util.IdentityHashMap<>();
      for (TimedTextToken primary : lyricTokens) {
         KaraokeTimeline timeline = primary.karaokeTimeline;
         if (timeline == null || timeline.tokenTimeMs.length < 2 || taken.containsKey(primary)) continue;
         TimedTextToken next = byOrder.get(primary.order + 1);
         if (next == null || next.karaokeTimeline != null || taken.containsKey(next) || attached.containsKey(next)) continue;
         long lastWordMs = timeline.tokenTimeMs[timeline.tokenTimeMs.length - 1];
         if (next.timeMs < primary.timeMs - MIXED_TRANSLATION_LEAD_MS || next.timeMs >= lastWordMs) continue;
         if (TextUtils.isEmpty(next.text) || TextUtils.equals(primary.text, next.text)) continue;
         attached.put(primary, next);
         taken.put(next, Boolean.TRUE);
      }
      if (!taken.isEmpty()) {
         lyricTokens.removeIf(taken::containsKey);
      }
      return attached;
   }

   private boolean isTranslationPairCandidate(@NonNull TimedTextToken primary, @NonNull TimedTextToken candidate) {
      if (candidate.nonLyric || !TextUtils.isEmpty(candidate.performerMarker)) {
         return false;
      } else {
         long dt = candidate.timeMs - primary.timeMs;
         if (dt >= 0L && dt <= 140L) {
            if (candidate.order <= primary.order) {
               return false;
            } else {
               return !TextUtils.equals(primary.text, candidate.text);
            }
         } else {
            return false;
         }
      }
   }

   @NonNull
   private ArrayList<TimedTextToken> parseTimedTokensForRawLine(@NonNull String rawLine, int baseOrder) {
      ArrayList<TimedTextToken> out = new ArrayList<>();
      Matcher matcher = LRC_TIME_PATTERN.matcher(rawLine);
      ArrayList<Long> stamps = new ArrayList<>();
      ArrayList<Integer> stampStarts = new ArrayList<>();
      ArrayList<Integer> stampEnds = new ArrayList<>();

      while(matcher.find()) {
         long stamp = this.parseTimestampMs(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
         if (stamp >= 0L) {
            stamps.add(stamp);
            stampStarts.add(matcher.start());
            stampEnds.add(matcher.end());
         }
      }

      if (stamps.isEmpty()) {
         return out;
      } else {
         String plainAfterTags = this.normalizeLyricLineText(this.collapseSpaces(LRC_TIME_PATTERN.matcher(rawLine).replaceAll("")));
         ArrayList<String> segments = new ArrayList<>(stamps.size());
         int nonEmptySegmentCount = 0;
         int singleSegmentIndex = -1;
         String singleSegmentText = null;

         for(int i = 0; i < stamps.size(); ++i) {
            int segStart = (Integer)stampEnds.get(i);
            int segEnd = i + 1 < stamps.size() ? (Integer)stampStarts.get(i + 1) : rawLine.length();
            if (segEnd < segStart) {
               segEnd = segStart;
            }

            String segment = this.normalizeTimedSegment(rawLine.substring(segStart, segEnd));
            segments.add(segment);
            if (this.hasVisibleTimedSegmentContent(segment)) {
               ++nonEmptySegmentCount;
               singleSegmentText = segment;
               singleSegmentIndex = i;
            }
         }

         if (stamps.size() > 1 && nonEmptySegmentCount >= 2) {
            StringBuilder textBuilder = new StringBuilder();
            ArrayList<Long> tokenTimeMs = new ArrayList<>();
            ArrayList<Integer> tokenEndCodePoint = new ArrayList<>();
            int codePointCursor = 0;

            for(int i = 0; i < segments.size(); ++i) {
               String segment = (String)segments.get(i);
               if (this.hasVisibleTimedSegmentContent(segment)) {
                  textBuilder.append(segment);
                  int cpCount = segment.codePointCount(0, segment.length());
                  if (cpCount > 0) {
                     codePointCursor += cpCount;
                     tokenTimeMs.add((Long)stamps.get(i));
                     tokenEndCodePoint.add(codePointCursor);
                  }
               }
            }

            // An empty timestamp after the last word is where that word ends (a held last note):
            // start times alone would give it only the length of the word before it.
            long terminalEndMs = -1L;
            if (!tokenTimeMs.isEmpty()) {
               long lastTokenMs = (Long)tokenTimeMs.get(tokenTimeMs.size() - 1);
               int lastVisible = -1;
               for(int i = 0; i < segments.size(); ++i) {
                  if (this.hasVisibleTimedSegmentContent((String)segments.get(i))) lastVisible = i;
               }
               if (lastVisible >= 0 && lastVisible + 1 < stamps.size() && (Long)stamps.get(lastVisible + 1) > lastTokenMs) {
                  terminalEndMs = (Long)stamps.get(lastVisible + 1);
               }
            }

            String rawLineText = textBuilder.length() > 0 ? textBuilder.toString() : plainAfterTags;
            int leadingTrimmedCodePoints = this.countLeadingLineEdgeWhitespaceCodePoints(rawLineText);
            String lineText = this.normalizeLyricLineText(rawLineText);
            if (this.shouldSkipLyricLineText(lineText)) {
               return out;
            } else {
               String performerMarker = this.extractPerformerMarkerLabel(lineText);
               boolean nonLyric = performerMarker == null && this.isLikelyNonLyricLine(lineText);
               KaraokeTimeline timeline = nonLyric || performerMarker != null
                     ? null
                     : this.buildKaraokeTimeline(tokenTimeMs, this.adjustTokenEndCodePointsForTrim(tokenEndCodePoint, leadingTrimmedCodePoints, lineText), lineText);
               if (timeline != null && terminalEndMs > 0L) timeline.terminalEndMs = terminalEndMs;
               long startTime = !tokenTimeMs.isEmpty() ? (Long)tokenTimeMs.get(0) : (Long)stamps.get(0);
               out.add(new TimedTextToken(startTime, baseOrder, lineText, nonLyric, timeline, performerMarker));
               return out;
            }
         } else {
            String lineText = this.normalizeLyricLineText(TextUtils.isEmpty(singleSegmentText) ? plainAfterTags : singleSegmentText);
            if (this.shouldSkipLyricLineText(lineText)) {
               return out;
            } else {
               String performerMarker = this.extractPerformerMarkerLabel(lineText);
               boolean nonLyric = performerMarker == null && this.isLikelyNonLyricLine(lineText);
               if (nonEmptySegmentCount == 1 && singleSegmentIndex >= 0 && singleSegmentIndex < stamps.size() - 1) {
                  long anchorTime = (Long)stamps.get(singleSegmentIndex);
                  out.add(new TimedTextToken(anchorTime, baseOrder, lineText, nonLyric, (KaraokeTimeline)null, performerMarker));
                  return out;
               } else {
                  for(int i = 0; i < stamps.size(); ++i) {
                     out.add(new TimedTextToken((Long)stamps.get(i), baseOrder + i, lineText, nonLyric, (KaraokeTimeline)null, performerMarker));
                  }

                  return out;
               }
            }
         }
      }
   }

   @Nullable
   private KaraokeTimeline buildKaraokeTimeline(@NonNull List<Long> tokenTimeMs, @NonNull List<Integer> tokenEndCodePoint, @NonNull String lineText) {
      int size = Math.min(tokenTimeMs.size(), tokenEndCodePoint.size());
      if (size <= 1) {
         return null;
      } else {
         int[] lineCodePoints = lineText.codePoints().toArray();
         int lineCodePointCount = lineCodePoints.length;
         long[] timeMs = new long[size];
         int[] endCp = new int[size];
         int cursor = 0;
         long lastTime = Long.MIN_VALUE;
         int lastEndCp = 0;

         for(int i = 0; i < size; ++i) {
            long stamp = (Long)tokenTimeMs.get(i);
            int end = Math.max(0, Math.min(lineCodePointCount, (Integer)tokenEndCodePoint.get(i)));
            if (end > lastEndCp) {
               if (cursor > 0 && stamp < lastTime) {
                  stamp = lastTime;
               }

               timeMs[cursor] = stamp;
               endCp[cursor] = end;
               lastTime = stamp;
               lastEndCp = end;
               ++cursor;
            }
         }

         if (cursor <= 1) {
            return null;
         } else {
            long[] compactTimeMs = timeMs;
            int[] compactEndCp = endCp;
            if (cursor != size) {
               compactTimeMs = new long[cursor];
               compactEndCp = new int[cursor];
               System.arraycopy(timeMs, 0, compactTimeMs, 0, cursor);
               System.arraycopy(endCp, 0, compactEndCp, 0, cursor);
            }

            return this.buildKaraokeTimelineWithTextShape(compactTimeMs, compactEndCp, lineCodePoints);
         }
      }
   }

   @NonNull
   private KaraokeTimeline buildKaraokeTimelineWithTextShape(@NonNull long[] tokenTimeMs, @NonNull int[] tokenEndCodePoint, @NonNull int[] lineCodePoints) {
      int tokenCount = Math.min(tokenTimeMs.length, tokenEndCodePoint.length);
      int[] tokenStartCodePoint = new int[tokenCount];
      int[] tokenVisibleCodePointCount = new int[tokenCount];
      int[] codePointVisibleOrdinalInToken = new int[lineCodePoints.length];

      for(int i = 0; i < codePointVisibleOrdinalInToken.length; ++i) {
         codePointVisibleOrdinalInToken[i] = -1;
      }

      int previousEnd = 0;

      for(int tokenIndex = 0; tokenIndex < tokenCount; ++tokenIndex) {
         int start = Math.max(0, Math.min(lineCodePoints.length, previousEnd));
         int end = Math.max(start, Math.min(lineCodePoints.length, tokenEndCodePoint[tokenIndex]));
         tokenStartCodePoint[tokenIndex] = start;
         int visibleOrdinal = 0;

         for(int codePointIndex = start; codePointIndex < end; ++codePointIndex) {
            if (!Character.isWhitespace(lineCodePoints[codePointIndex])) {
               codePointVisibleOrdinalInToken[codePointIndex] = visibleOrdinal++;
            }
         }

         tokenVisibleCodePointCount[tokenIndex] = visibleOrdinal;
         previousEnd = end;
      }

      long averageGlyphIntervalMs = this.resolveAverageKaraokeGlyphIntervalMs(
            tokenTimeMs,
            tokenStartCodePoint,
            tokenEndCodePoint,
            tokenVisibleCodePointCount,
            codePointVisibleOrdinalInToken,
            lineCodePoints
      );
      return new KaraokeTimeline(tokenTimeMs, tokenStartCodePoint, tokenEndCodePoint, tokenVisibleCodePointCount, codePointVisibleOrdinalInToken, averageGlyphIntervalMs);
   }

   private long resolveAverageKaraokeGlyphIntervalMs(
         @NonNull long[] tokenTimeMs,
         @NonNull int[] tokenStartCodePoint,
         @NonNull int[] tokenEndCodePoint,
         @NonNull int[] tokenVisibleCodePointCount,
         @NonNull int[] codePointVisibleOrdinalInToken,
         @NonNull int[] lineCodePoints
   ) {
      int tokenCount = Math.min(
            Math.min(tokenTimeMs.length, tokenStartCodePoint.length),
            Math.min(tokenEndCodePoint.length, tokenVisibleCodePointCount.length)
      );
      if (tokenCount <= 0 || lineCodePoints.length <= 1) {
         return KARAOKE_GLYPH_LIFT_MIN_DURATION_MS;
      }

      double intervalSumMs = 0.0;
      int intervalCount = 0;
      double previousUnitStartMs = Double.NaN;
      int local = 0;
      while(local < lineCodePoints.length) {
         int unitEnd = this.resolveKaraokeLanguageUnitEnd(lineCodePoints, local);
         double unitStartMs = Double.POSITIVE_INFINITY;
         for(int codePointIndex = local; codePointIndex < unitEnd; ++codePointIndex) {
            double codePointStartMs = this.resolveKaraokeCodePointStartTimeMs(
                  codePointIndex,
                  tokenTimeMs,
                  tokenStartCodePoint,
                  tokenEndCodePoint,
                  tokenVisibleCodePointCount,
                  codePointVisibleOrdinalInToken,
                  tokenCount
            );
            unitStartMs = Math.min(unitStartMs, codePointStartMs);
         }

         if (unitStartMs != Double.POSITIVE_INFINITY) {
            if (!Double.isNaN(previousUnitStartMs)) {
               double intervalMs = unitStartMs - previousUnitStartMs;
               if (intervalMs > 0.0 && intervalMs <= (double)KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS) {
                  intervalSumMs += intervalMs;
                  ++intervalCount;
               }
            }

            previousUnitStartMs = unitStartMs;
         }

         local = Math.max(local + 1, unitEnd);
      }

      return intervalCount > 0
            ? Math.max(1L, Math.round(intervalSumMs / (double)intervalCount))
            : KARAOKE_GLYPH_LIFT_MIN_DURATION_MS;
   }

   private double resolveKaraokeCodePointStartTimeMs(
         int codePointIndex,
         @NonNull long[] tokenTimeMs,
         @NonNull int[] tokenStartCodePoint,
         @NonNull int[] tokenEndCodePoint,
         @NonNull int[] tokenVisibleCodePointCount,
         @NonNull int[] codePointVisibleOrdinalInToken,
         int tokenCount
   ) {
      if (codePointIndex < 0 || codePointIndex >= codePointVisibleOrdinalInToken.length) {
         return Double.POSITIVE_INFINITY;
      }

      for(int tokenIndex = 0; tokenIndex < tokenCount; ++tokenIndex) {
         if (codePointIndex < tokenStartCodePoint[tokenIndex]) {
            break;
         }

         if (codePointIndex >= tokenEndCodePoint[tokenIndex]) {
            continue;
         }

         int visibleOrdinal = codePointVisibleOrdinalInToken[codePointIndex];
         int visibleCount = Math.max(0, tokenVisibleCodePointCount[tokenIndex]);
         if (visibleOrdinal < 0 || visibleCount <= 0) {
            return Double.POSITIVE_INFINITY;
         }

         long tokenDurationMs = this.resolveKaraokeTokenDurationMs(tokenTimeMs, tokenIndex, tokenCount);
         double codePointDurationMs = (double)Math.max(1L, tokenDurationMs) / (double)visibleCount;
         return (double)tokenTimeMs[tokenIndex] + codePointDurationMs * (double)visibleOrdinal;
      }

      return Double.POSITIVE_INFINITY;
   }

   private long resolveKaraokeTokenDurationMs(@NonNull long[] tokenTimeMs, int tokenIndex, int tokenCount) {
      long tokenStartMs = tokenTimeMs[tokenIndex];
      if (tokenIndex + 1 < tokenCount && tokenTimeMs[tokenIndex + 1] > tokenStartMs) {
         return tokenTimeMs[tokenIndex + 1] - tokenStartMs;
      }

      if (tokenIndex > 0 && tokenStartMs > tokenTimeMs[tokenIndex - 1]) {
         return tokenStartMs - tokenTimeMs[tokenIndex - 1];
      }

      return KARAOKE_TERMINAL_SCAN_MS;
   }

   private int resolveKaraokeLanguageUnitEnd(@NonNull int[] codePoints, int startIndex) {
      int count = codePoints.length;
      int local = Math.max(0, Math.min(count, startIndex));
      if (local >= count) {
         return count;
      }

      int cp = codePoints[local];
      if (this.isKaraokeWordRunStartCodePoint(cp)) {
         ++local;
         while(local < count) {
            int next = codePoints[local];
            if (this.isKaraokeWordRunContinuationCodePoint(next)) {
               ++local;
            } else if (this.isKaraokeWordJoinerCodePoint(next) && local + 1 < count && this.isKaraokeWordRunContinuationCodePoint(codePoints[local + 1])) {
               ++local;
            } else {
               break;
            }
         }

         while(local < count && this.isKaraokeTrailingWordPunctuationCodePoint(codePoints[local])) {
            ++local;
         }

         while(local < count && this.isKaraokeGlyphWhitespaceCodePoint(codePoints[local])) {
            ++local;
         }
      } else if (this.isKaraokeGlyphWhitespaceCodePoint(cp)) {
         do {
            ++local;
         } while(local < count && this.isKaraokeGlyphWhitespaceCodePoint(codePoints[local]));
      } else {
         ++local;
      }

      return local;
   }

   private boolean isKaraokeWordRunStartCodePoint(int cp) {
      if (Character.isDigit(cp)) {
         return true;
      }

      if (!Character.isLetter(cp)) {
         return false;
      }

      Character.UnicodeScript script = Character.UnicodeScript.of(cp);
      return script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK || script == Character.UnicodeScript.CYRILLIC;
   }

   private boolean isKaraokeWordRunContinuationCodePoint(int cp) {
      int type = Character.getType(cp);
      return type == Character.NON_SPACING_MARK
            || type == Character.ENCLOSING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || this.isKaraokeWordRunStartCodePoint(cp);
   }

   private boolean isKaraokeWordJoinerCodePoint(int cp) {
      return cp == 39 || cp == 8217 || cp == 700 || cp == 45 || cp == 8208 || cp == 8209 || cp == 46 || cp == 47 || cp == 38;
   }

   private boolean isKaraokeTrailingWordPunctuationCodePoint(int cp) {
      return cp == 33
            || cp == 34
            || cp == 37
            || cp == 41
            || cp == 44
            || cp == 46
            || cp == 58
            || cp == 59
            || cp == 63
            || cp == 93
            || cp == 125
            || cp == 161
            || cp == 191
            || cp == 8217
            || cp == 8221
            || cp == 8230
            || cp == 12289
            || cp == 12290
            || cp == 65281
            || cp == 65292
            || cp == 65306
            || cp == 65307
            || cp == 65311
            || cp == 65373
            || cp == 65341;
   }

   private boolean isKaraokeGlyphWhitespaceCodePoint(int cp) {
      return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
   }

   @Nullable
   private KaraokeTimeline buildMappedTranslationKaraokeTimeline(@NonNull KaraokeTimeline sourceTimeline, @NonNull String translationText) {
      String text = this.normalizeLyricLineText(translationText);
      if (TextUtils.isEmpty(text)) {
         return null;
      }

      int[] translationCodePoints = text.codePoints().toArray();
      int visibleTranslationCount = this.countVisibleCodePoints(translationCodePoints);
      int tokenCount = Math.min(sourceTimeline.tokenTimeMs.length, sourceTimeline.tokenEndCodePoint.length);
      if (tokenCount <= 1 || visibleTranslationCount <= 1) {
         return null;
      }

      int totalSourceVisible = 0;
      for(int i = 0; i < tokenCount && i < sourceTimeline.tokenVisibleCodePointCount.length; ++i) {
         totalSourceVisible += Math.max(0, sourceTimeline.tokenVisibleCodePointCount[i]);
      }

      long[] mappedTimeMs = new long[tokenCount];
      int[] mappedEndCodePoint = new int[tokenCount];
      int cumulativeSourceVisible = 0;

      for(int i = 0; i < tokenCount; ++i) {
         mappedTimeMs[i] = sourceTimeline.tokenTimeMs[i];
         if (totalSourceVisible > 0) {
            int visibleCount = i < sourceTimeline.tokenVisibleCodePointCount.length ? Math.max(0, sourceTimeline.tokenVisibleCodePointCount[i]) : 0;
            cumulativeSourceVisible += visibleCount;
            int visibleEnd = Math.round((float)visibleTranslationCount * (float)cumulativeSourceVisible / (float)totalSourceVisible);
            mappedEndCodePoint[i] = this.resolveCodePointEndForVisibleCount(translationCodePoints, visibleEnd);
         } else {
            int visibleEnd = Math.round((float)visibleTranslationCount * (float)(i + 1) / (float)tokenCount);
            mappedEndCodePoint[i] = this.resolveCodePointEndForVisibleCount(translationCodePoints, visibleEnd);
         }
      }

      mappedEndCodePoint[tokenCount - 1] = translationCodePoints.length;
      ArrayList<Long> compactTimeMs = new ArrayList<>(tokenCount);
      ArrayList<Integer> compactEndCodePoint = new ArrayList<>(tokenCount);
      int lastEnd = 0;
      long lastTime = Long.MIN_VALUE;
      for(int i = 0; i < tokenCount; ++i) {
         int end = Math.max(0, Math.min(translationCodePoints.length, mappedEndCodePoint[i]));
         if (end > lastEnd) {
            long time = mappedTimeMs[i];
            if (!compactTimeMs.isEmpty() && time < lastTime) {
               time = lastTime;
            }

            compactTimeMs.add(time);
            compactEndCodePoint.add(end);
            lastEnd = end;
            lastTime = time;
         }
      }

      KaraokeTimeline mapped = this.buildKaraokeTimeline(compactTimeMs, compactEndCodePoint, text);
      // The translation's last word is held as long as the line's.
      if (mapped != null) mapped.terminalEndMs = sourceTimeline.terminalEndMs;
      return mapped;
   }

   private int countVisibleCodePoints(@NonNull int[] codePoints) {
      int count = 0;
      for(int i = 0; i < codePoints.length; ++i) {
         if (!Character.isWhitespace(codePoints[i])) {
            ++count;
         }
      }

      return count;
   }

   private int resolveCodePointEndForVisibleCount(@NonNull int[] codePoints, int visibleEnd) {
      if (visibleEnd <= 0) {
         return 0;
      }

      int visibleSeen = 0;
      for(int i = 0; i < codePoints.length; ++i) {
         if (!Character.isWhitespace(codePoints[i])) {
            ++visibleSeen;
            if (visibleSeen >= visibleEnd) {
               int end = i + 1;
               while(end < codePoints.length && Character.isWhitespace(codePoints[end])) {
                  ++end;
               }

               return end;
            }
         }
      }

      return codePoints.length;
   }

   @NonNull
   private String normalizeTimedSegment(@Nullable String segment) {
      return TextUtils.isEmpty(segment) ? "" : segment.replace('\ufeff', ' ').replace('\t', ' ');
   }

   @NonNull
   private String normalizeLyricLineText(@Nullable String text) {
      if (TextUtils.isEmpty(text)) {
         return "";
      } else {
         String normalized = text.replace('\ufeff', ' ').replace('\t', ' ');
         int start = 0;
         int end = normalized.length();

         while(start < end) {
            int cp = normalized.codePointAt(start);
            if (!this.isLineEdgeWhitespace(cp)) {
               break;
            }

            start += Character.charCount(cp);
         }

         while(end > start) {
            int cp = normalized.codePointBefore(end);
            if (!this.isLineEdgeWhitespace(cp)) {
               break;
            }

            end -= Character.charCount(cp);
         }

         return start == 0 && end == normalized.length() ? normalized : normalized.substring(start, end);
      }
   }

   private boolean shouldSkipLyricLineText(@Nullable String text) {
      String normalized = this.normalizeLyricLineText(text);
      return TextUtils.isEmpty(normalized)
            || ".".equals(normalized)
            || LyricLineSanitizer.isStandaloneSlashPlaceholder(normalized);
   }

   private int countLeadingLineEdgeWhitespaceCodePoints(@Nullable String text) {
      if (TextUtils.isEmpty(text)) {
         return 0;
      } else {
         int count = 0;
         int index = 0;

         while(index < text.length()) {
            int cp = text.codePointAt(index);
            if (!this.isLineEdgeWhitespace(cp)) {
               break;
            }

            ++count;
            index += Character.charCount(cp);
         }

         return count;
      }
   }

   @NonNull
   private List<Integer> adjustTokenEndCodePointsForTrim(@NonNull List<Integer> tokenEndCodePoint, int leadingTrimmedCodePoints, @NonNull String trimmedLineText) {
      int lineCodePointCount = trimmedLineText.codePointCount(0, trimmedLineText.length());
      ArrayList<Integer> adjusted = new ArrayList<>(tokenEndCodePoint.size());

      for(int i = 0; i < tokenEndCodePoint.size(); ++i) {
         int end = (Integer)tokenEndCodePoint.get(i) - Math.max(0, leadingTrimmedCodePoints);
         if (end < 0) {
            end = 0;
         }

         if (end > lineCodePointCount) {
            end = lineCodePointCount;
         }

         adjusted.add(end);
      }

      return adjusted;
   }

   private boolean isLineEdgeWhitespace(int cp) {
      return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
   }

   private boolean hasVisibleTimedSegmentContent(@Nullable String segment) {
      if (TextUtils.isEmpty(segment)) {
         return false;
      } else {
         int[] cps = segment.codePoints().toArray();

         for(int i = 0; i < cps.length; ++i) {
            if (!Character.isWhitespace(cps[i])) {
               return true;
            }
         }

         return false;
      }
   }

   private void refreshNonLyricHint(long renderPositionMs) {
      if (this.tickerEnabled && !this.timedNonLyricLines.isEmpty()) {
         int index = this.findNonLyricHintIndex(renderPositionMs + 140L);
         if (index >= 0 && index < this.timedNonLyricLines.size()) {
            TimedLine line = (TimedLine)this.timedNonLyricLines.get(index);
            long dt = renderPositionMs - line.timeMs;
            if (dt >= -140L && dt <= 2200L) {
               if (!TextUtils.equals(this.currentNonLyricHintText, line.text)) {
                  this.currentNonLyricHintText = line.text;
                  this.nonLyricHintView.setTextContent(line.text);
                  this.requestLayout();
               }

               float alpha = 0.68F;
               if (dt < 0L) {
                  alpha *= this.clampAlpha((float)(dt + 140L) / 140.0F);
               } else if (dt > 1940L) {
                  alpha *= 1.0F - this.clampAlpha((float)(dt - 1940L) / 260.0F);
               }

               this.nonLyricHintView.setAlpha(alpha);
               if (this.nonLyricHintView.getVisibility() != 0) {
                  this.nonLyricHintView.setVisibility(0);
               }

            } else {
               this.hideNonLyricHintView();
	            }
	         } else {
            this.hideNonLyricHintView();
         }
      } else {
         this.hideNonLyricHintView();
      }
   }

   private void hideNonLyricHintView() {
      this.currentNonLyricHintText = null;
      if (this.nonLyricHintView.getVisibility() != 8 || this.nonLyricHintView.getAlpha() != 0.0F) {
         this.nonLyricHintView.setAlpha(0.0F);
         this.nonLyricHintView.setVisibility(8);
      }

   }

   private int findNonLyricHintIndex(long timeMs) {
      int low = 0;
      int high = this.timedNonLyricLines.size() - 1;
      int answer = -1;

      while(low <= high) {
         int mid = low + high >>> 1;
         long stamp = ((TimedLine)this.timedNonLyricLines.get(mid)).timeMs;
         if (stamp <= timeMs) {
            answer = mid;
            low = mid + 1;
         } else {
            high = mid - 1;
         }
      }

      return answer;
   }

   /**
    * Recognizes a standalone singer/role declaration without sharing the existing credit-line
    * classifier. A performer marker must consume the complete line and end in a colon; optional
    * matching brackets may wrap either the declaration or the name itself.
    */
   @Nullable
   private String extractPerformerMarkerLabel(@Nullable String text) {
      String normalized = this.normalizeLyricLineText(text);
      if (TextUtils.isEmpty(normalized)) return null;

      String declaration = this.stripMatchingPerformerWrappers(normalized);
      if (TextUtils.isEmpty(declaration)) return null;
      int end = declaration.length();
      while(end > 0 && this.isLineEdgeWhitespace(declaration.codePointBefore(end))) {
         end -= Character.charCount(declaration.codePointBefore(end));
      }
      if (end <= 0) return null;
      char terminal = declaration.charAt(end - 1);
      if (terminal != ':' && terminal != '：') return null;

      String head = this.stripMatchingPerformerWrappers(
            declaration.substring(0, end - 1).trim()
      );
      if (TextUtils.isEmpty(head) || this.findColonIndex(head) >= 0) return null;
      int codePointCount = head.codePointCount(0, head.length());
      if (codePointCount <= 0 || codePointCount > 40) return null;

      String simplified = this.simplifyMetaToken(head);
      if (TextUtils.isEmpty(simplified)
            || this.isPerformerMetadataHead(simplified)
            || this.isPerformerSectionHead(simplified)
            || !this.isLikelyPerformerLabel(head, simplified)) {
         return null;
      }

      String display = this.collapseSpaces(head).trim();
      if ("合".equals(display)) return "合唱";
      return TextUtils.isEmpty(display) ? null : display;
   }

   @NonNull
   private String stripMatchingPerformerWrappers(@Nullable String value) {
      String result = value == null ? "" : value.trim();
      boolean stripped = true;
      while(stripped && result.length() >= 2) {
         stripped = false;
         char first = result.charAt(0);
         char last = result.charAt(result.length() - 1);
         if (this.isMatchingPerformerWrapper(first, last)) {
            result = result.substring(1, result.length() - 1).trim();
            stripped = true;
         }
      }
      return result;
   }

   private boolean isMatchingPerformerWrapper(char first, char last) {
      return first == '(' && last == ')'
            || first == '（' && last == '）'
            || first == '[' && last == ']'
            || first == '【' && last == '】'
            || first == '〔' && last == '〕'
            || first == '「' && last == '」'
            || first == '『' && last == '』';
   }

   private boolean isPerformerMetadataHead(@NonNull String simplifiedHead) {
      for(String keyword : PERFORMER_METADATA_HEAD_KEYWORDS) {
         String simplifiedKeyword = this.simplifyMetaToken(keyword);
         boolean exactOnly = simplifiedKeyword.codePointCount(0, simplifiedKeyword.length()) == 1;
         if (!TextUtils.isEmpty(simplifiedKeyword)
               && (simplifiedHead.equals(simplifiedKeyword)
               || !exactOnly && simplifiedHead.startsWith(simplifiedKeyword))) {
            return true;
         }
      }
      return false;
   }

   private boolean isPerformerSectionHead(@NonNull String simplifiedHead) {
      for(String keyword : PERFORMER_SECTION_HEAD_KEYWORDS) {
         String simplifiedKeyword = this.simplifyMetaToken(keyword);
         if (!TextUtils.isEmpty(simplifiedKeyword)
               && (simplifiedHead.equals(simplifiedKeyword)
               || simplifiedHead.startsWith(simplifiedKeyword))) {
            return true;
         }
      }
      return false;
   }

   private boolean isLikelyPerformerLabel(@NonNull String head,
                                          @NonNull String simplifiedHead) {
      if (this.isKnownPerformerRole(simplifiedHead)) return true;
      String[] segments = head.split("[/／、,&＆+＋·•・]+");
      if (segments.length == 0 || segments.length > 8) return false;
      for(String segment : segments) {
         if (!this.isLikelyPerformerNameSegment(segment.trim())) return false;
      }
      return true;
   }

   private boolean isKnownPerformerRole(@NonNull String simplifiedHead) {
      switch(simplifiedHead) {
         case "合":
         case "合唱":
         case "齐唱":
         case "对唱":
         case "独唱":
         case "轮唱":
         case "全体":
         case "众人":
         case "男":
         case "男声":
         case "女":
         case "女声":
         case "童声":
         case "主唱":
         case "领唱":
         case "chorus":
         case "choir":
         case "together":
         case "all":
         case "both":
         case "duet":
         case "solo":
         case "male":
         case "female":
         case "man":
         case "woman":
         case "group":
         case "ensemble":
         case "everyone":
         case "lead":
            return true;
         default:
            return false;
      }
   }

   private boolean isLikelyPerformerNameSegment(@Nullable String segment) {
      if (TextUtils.isEmpty(segment)) return false;
      int[] codePoints = segment.codePoints().toArray();
      if (codePoints.length == 0 || codePoints.length > 32) return false;
      int cjkCount = 0;
      int letterOrDigitCount = 0;
      int wordCount = 0;
      boolean insideWord = false;
      for(int cp : codePoints) {
         boolean cjk = cp >= 0x3400 && cp <= 0x9FFF;
         boolean word = cjk || Character.isLetterOrDigit(cp);
         if (cjk) ++cjkCount;
         if (word) {
            ++letterOrDigitCount;
            if (!insideWord) ++wordCount;
            insideWord = true;
         } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            insideWord = false;
         } else if (cp == '-' || cp == '‐' || cp == '‑' || cp == '—'
               || cp == '\'' || cp == '’' || cp == '.' || cp == '．' || cp == '_') {
            insideWord = false;
         } else {
            return false;
         }
      }
      if (letterOrDigitCount == 0) return false;
      if (cjkCount > 0) return codePoints.length <= 10;
      return wordCount > 0 && wordCount <= 5;
   }

   private boolean isLikelyNonLyricLine(@Nullable String text) {
      if (TextUtils.isEmpty(text)) {
         return false;
      } else {
         String normalized = text.trim();
         if (TextUtils.isEmpty(normalized)) {
            return false;
         } else {
            String lower = normalized.toLowerCase(Locale.ROOT);

            for(String phrase : NON_LYRIC_PHRASE_KEYWORDS) {
               if (lower.contains(phrase)) {
                  return true;
               }
            }

            int colonIndex = this.findColonIndex(normalized);
            if (colonIndex > 0 && colonIndex <= 16) {
               String head = this.simplifyMetaToken(normalized.substring(0, colonIndex));
               if (this.isLikelyNonLyricHead(head)) {
                  return true;
               }
            }

            return false;
         }
      }
   }

   private int findColonIndex(@NonNull String text) {
      int ascii = text.indexOf(58);
      int cjk = text.indexOf(65306);
      if (ascii < 0) {
         return cjk;
      } else {
         return cjk < 0 ? ascii : Math.min(ascii, cjk);
      }
   }

   private boolean isLikelyNonLyricHead(@Nullable String head) {
      if (TextUtils.isEmpty(head)) {
         return false;
      } else {
         String lower = head.toLowerCase(Locale.ROOT);

         for(String keyword : NON_LYRIC_HEAD_KEYWORDS) {
            String token = this.simplifyMetaToken(keyword);
            if (!TextUtils.isEmpty(token) && (lower.equals(token) || lower.contains(token) || token.contains(lower))) {
               return true;
            }
         }

         return false;
      }
   }

   @NonNull
   private String simplifyMetaToken(@Nullable String text) {
      if (TextUtils.isEmpty(text)) {
         return "";
      } else {
         StringBuilder sb = new StringBuilder(text.length());

         for(int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
               sb.append(Character.toLowerCase(c));
            } else if (c >= 19968 && c <= '鿿') {
               sb.append(c);
            }
         }

         return sb.toString();
      }
   }

   @NonNull
   private String normalizeNonLyricHintText(@Nullable String text) {
      if (TextUtils.isEmpty(text)) {
         return "";
      } else {
         String normalized;
         for(normalized = text.trim().replace('\t', ' '); normalized.contains("  "); normalized = normalized.replace("  ", " ")) {
         }

         int colon = this.findColonIndex(normalized);
         if (colon > 0 && colon < normalized.length() - 1) {
            String head = normalized.substring(0, colon).trim();
            String tail = normalized.substring(colon + 1).trim();
            normalized = head + "：" + tail;
         }

         if (normalized.length() > 28) {
            normalized = normalized.substring(0, 27) + "…";
         }

         return normalized;
      }
   }

   public void setTickerEnabled(boolean enabled) {
      if (this.tickerEnabled != enabled) {
	         this.tickerEnabled = enabled;
	         if (!this.tickerEnabled) {
	            this.playbackRunning = false;
	            this.finishBrowseHandoff();
	            this.manualTouchDown = false;
	            this.cancelScheduledManualScrollResume();
	            this.stopManualScrollFling();
	            this.recycleManualVelocityTracker();
	            this.manualScrollActive = false;
	            this.syncPassedReveal();
	            this.manualScrollDragging = false;
	            this.manualScrollNoBlur = false;
	            this.stopTimedLyricsFrameLoop();
	            this.stopTrackAnimator();
	            this.clearRowLagAnimations();
	            this.refreshPrepCountdownRows(this.resolveRenderPositionMs());
	            this.hideNonLyricHintView();
         } else {
            this.rebuildForTextStyleIfPending(true);
            this.reportedUptimeMs = SystemClock.uptimeMillis();
            this.resolveAndApplyActiveSource(true);
            this.scheduleTimedLyricsFrameLoopIfNeeded();
         }

      }
   }

   protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      if(this.rowBuildActive)android.view.Choreographer.getInstance().postFrameCallback(this.rowBuildFrame);
      if (!this.lagChain.isNative()) {
         this.lagChain = new LyricLagChain((float)this.rowHeightPx);
         if (this.lyricsStyleStore != null) this.lagChain.setGain(this.lyricsStyleStore.getPullScale());
      }
      if (this.lyricsStyleStore != null) {
         this.lyricsStyleStore.addListener(this.lyricsStyleListener);
         this.applyLyricsStyle();
      }
      this.rebuildForTextStyleIfPending(this.tickerEnabled || this.compactPlaybackClockEnabled);
      this.scheduleTimedLyricsFrameLoopIfNeeded();
   }

	   protected void onDetachedFromWindow() {
	      this.cancelTranslationToggleAnimator();
	      this.finishBrowseHandoff();
	      this.manualTouchDown = false;
	      this.cancelScheduledManualScrollResume();
	      this.stopManualScrollFling();
	      this.recycleManualVelocityTracker();
	      this.manualScrollActive = false;
	      this.syncPassedReveal();
	      this.manualScrollDragging = false;
	      this.manualScrollNoBlur = false;
      this.hideNonLyricHintView();
      this.stopTimedLyricsFrameLoop();
      this.clearRowLagAnimations();
      this.lagChain.release();
      if (this.lyricsStyleStore != null) this.lyricsStyleStore.removeListener(this.lyricsStyleListener);
      if (this.lyricsStyleRebuildPosted) {
         // The posted rebuild would not run while detached: it runs on the next attach instead.
         this.removeCallbacks(this.lyricsStyleRebuild);
         this.lyricsStyleRebuildPosted = false;
         this.lyricsStyleRebuildPending = true;
      }
      android.view.Choreographer.getInstance().removeFrameCallback(this.rowBuildFrame);
      super.onDetachedFromWindow();
   }

   public void setPlaybackState(long positionMs, boolean playing) {
      this.setPlaybackState(positionMs, playing, false);
   }

   public void setPlaybackState(long positionMs, boolean playing, boolean forcePosition) {
      this.setPlaybackState(positionMs, playing, forcePosition, 1.0F);
   }

   public void setPlaybackState(long positionMs, boolean playing, boolean forcePosition, float playbackSpeed) {
      this.setPlaybackStateInternal(positionMs, playing, forcePosition, playbackSpeed, false);
   }

   /** Keeps the lyric clock interpolating even while the full ticker UI is hidden. */
   public void setCompactPlaybackState(long positionMs, boolean playing, float playbackSpeed) {
      this.setPlaybackStateInternal(positionMs, playing, false, playbackSpeed, true);
   }

   /** Compact-clock variant used when a seek or media replacement must snap immediately. */
   public void setCompactPlaybackState(long positionMs,
                                       boolean playing,
                                       boolean forcePosition,
                                       float playbackSpeed) {
      this.setPlaybackStateInternal(positionMs, playing, forcePosition, playbackSpeed, true);
   }

   private void setPlaybackStateInternal(long positionMs,
                                         boolean playing,
                                         boolean forcePosition,
                                         float playbackSpeed,
                                         boolean interpolateWhileTickerHidden) {
      this.rebuildForTextStyleIfPending(this.tickerEnabled || interpolateWhileTickerHidden);
      long now = SystemClock.uptimeMillis();
      long safePositionMs = Math.max(0L, positionMs - this.lyricsDelayMs);
      float safePlaybackSpeed = sanitizePlaybackSpeed(playbackSpeed);
      long previousRenderPositionMs = this.resolveRenderPositionMs(now);
      long syncDriftMs = safePositionMs - previousRenderPositionMs;
      boolean canSmoothSyncDrift = !forcePosition
            && (this.tickerEnabled || interpolateWhileTickerHidden)
            && this.playbackRunning
            && playing
            && !this.progressScrubbingActive
            && this.reportedUptimeMs > 0L
            && Math.abs(syncDriftMs) <= PLAYBACK_VISUAL_SYNC_SNAP_MS;
      boolean positionDiscontinuous = !canSmoothSyncDrift
            && (forcePosition || Math.abs(syncDriftMs) > PLAYBACK_VISUAL_SYNC_SNAP_MS);
      if (positionDiscontinuous) {
         this.clearTimedVisualProgressState();
      }

      if (canSmoothSyncDrift) {
         long correctionMs = Math.round((float)syncDriftMs * PLAYBACK_VISUAL_SYNC_CORRECTION_RATIO);
         correctionMs = Math.max(-PLAYBACK_VISUAL_SYNC_MAX_STEP_MS, Math.min(PLAYBACK_VISUAL_SYNC_MAX_STEP_MS, correctionMs));
         this.reportedPlaybackPositionMs = Math.max(0L, previousRenderPositionMs + correctionMs);
      } else {
         this.reportedPlaybackPositionMs = safePositionMs;
      }

      this.reportedPlaybackSpeed = safePlaybackSpeed;
      this.playbackRunning = playing;
      this.compactPlaybackClockEnabled = interpolateWhileTickerHidden;
      if(this.rowBuildActive){this.reportedUptimeMs=now;return;}
      if (this.manualScrollActive) {
         this.reportedUptimeMs = now;
         if (!playing || this.progressScrubbingActive) {
            this.cancelScheduledManualScrollResume();
            this.stopTimedLyricsFrameLoop();
         } else {
            this.scheduleManualScrollResume();
         }

         // Browsing only holds the track: the highlight keeps following the song.
         if (this.tickerEnabled) {
            this.resolveAndApplyActiveSource(this.progressScrubbingActive);
            this.scheduleTimedLyricsFrameLoopIfNeeded();
         }
      } else {
         if (!playing || this.progressScrubbingActive) {
            this.stopTimedLyricsFrameLoop();
         }

         this.reportedUptimeMs = now;
         if (this.tickerEnabled) {
            this.resolveAndApplyActiveSource(this.progressScrubbingActive);
            this.scheduleTimedLyricsFrameLoopIfNeeded();
         }
      }
   }

   public boolean hasTranslationContent() {
      return this.hasTranslationContent;
   }

   public boolean isTranslationVisible() {
      return this.translationVisible;
   }

   public void setOnTranslationStateChangeListener(@Nullable OnTranslationStateChangeListener listener) {
      this.translationStateChangeListener = listener;
      this.dispatchTranslationStateChangedIfNeeded();
   }

   public void setOnRowsReadyListener(@Nullable OnRowsReadyListener listener) {
      this.rowsReadyListener = listener;
   }

   public void setOnLyricsErrorListener(@Nullable Runnable listener) {
      this.lyricsErrorListener = listener;
   }

   public void setOnLyricSeekListener(@Nullable OnLyricSeekListener listener) {
      this.lyricSeekListener = listener;
   }

   public void setWideAnchorMode(boolean enabled) {
      if (this.wideAnchorMode != enabled) {
         this.wideAnchorMode = enabled;
         if (!this.manualScrollActive && (this.trackAnimator == null || !this.trackAnimator.isRunning())) {
            this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
            this.invalidate();
         }

      }
   }

   private void setCenterAnchor(boolean enabled) {
      if (this.centerAnchor == enabled) {
         return;
      }
      this.centerAnchor = enabled;
      // Passed lines come back (or leave) and the lines move to the new resting place at once.
      this.appliedFocusGeometrySignature = Long.MIN_VALUE;
      this.refreshRowAlphaState(this.resolveRenderPositionMs());
      this.refreshRowContentPresentationScale();
      if (!this.manualScrollActive && (this.trackAnimator == null || !this.trackAnimator.isRunning())) {
         this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
      } else {
         this.trackContainer.setTranslationY(this.clampTrackTranslationY(this.trackContainer.getTranslationY()));
      }
      this.refreshRowEdgeFadeState();
      this.invalidate();
   }

   public boolean hasPreparedRows() {
      return !this.rowBuildActive && !this.rowHolders.isEmpty();
   }

   /** New lyrics text is still being parsed; the rows on screen still show the previous text. */
   public boolean isLyricsParsePending() {
      return this.lyricsParseInFlight;
   }

   /**
    * How far the lyrics have entered: 0 hidden, 1 in place. In between, every visible line is
    * offset below its place and faded by its own share of the run, the upper lines leading, and
    * rises in with a slight overshoot. Leaving runs the same curve backwards, so a reversal at
    * any moment continues each line from exactly where it is. The view itself stays opaque: no
    * group alpha, so no offscreen layer for the whole lyrics area while it plays.
    */
   public float getPresentationProgress() {
      return this.presentationProgress;
   }

   public boolean isPresentationAnimating() {
      return this.presentationAnimator != null;
   }

   public void setPresentationProgress(float progress) {
      this.cancelPresentationAnimator();
      this.presentationTarget = this.clampAlpha(progress);
      this.presentationProgress = this.presentationTarget;
      this.applyPresentationToRows();
   }

   /** Moves toward shown or hidden from wherever the lines are now; endAction runs only on arrival. */
   public void animatePresentation(boolean show, @Nullable final Runnable endAction) {
      final float target = show ? 1.0F : 0.0F;
      if (this.presentationAnimator != null && Math.abs(this.presentationTarget - target) < 0.001F) {
         this.presentationEndAction = endAction;
         return;
      }

      this.cancelPresentationAnimator();
      this.presentationTarget = target;
      float start = this.presentationProgress;
      float distance = Math.abs(target - start);
      if (distance < 0.001F) {
         this.presentationProgress = target;
         this.applyPresentationToRows();
         if (endAction != null) {
            endAction.run();
         }
         return;
      }

      long fullDurationMs = show ? PRESENTATION_ENTER_DURATION_MS : PRESENTATION_EXIT_DURATION_MS;
      final ValueAnimator animator = com.lab.hv.RustValueAnimator.ofFloat(start, target);
      animator.setDuration(Math.max(90L, Math.round((float)fullDurationMs * distance)));
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener((a) -> {
         if (this.presentationAnimator != animator) {
            return;
         }

         this.presentationProgress = (Float)a.getAnimatedValue();
         this.applyPresentationToRows();
      });
      animator.addListener(new AnimatorListenerAdapter() {
         private boolean cancelled;

         public void onAnimationCancel(Animator animation) {
            this.cancelled = true;
         }

         public void onAnimationEnd(Animator animation) {
            if (this.cancelled || NowPlayingLyricsTickerView.this.presentationAnimator != animator) {
               return;
            }

            NowPlayingLyricsTickerView.this.presentationAnimator = null;
            NowPlayingLyricsTickerView.this.presentationProgress = target;
            NowPlayingLyricsTickerView.this.applyPresentationToRows();
            Runnable action = NowPlayingLyricsTickerView.this.presentationEndAction;
            NowPlayingLyricsTickerView.this.presentationEndAction = null;
            if (action != null) {
               action.run();
            }
         }
      });
      this.presentationEndAction = endAction;
      this.presentationAnimator = animator;
      animator.start();
   }

   private void cancelPresentationAnimator() {
      ValueAnimator animator = this.presentationAnimator;
      this.presentationAnimator = null;
      this.presentationEndAction = null;
      if (animator != null) {
         animator.cancel();
      }
   }

   private void applyPresentationToRows() {
      float progress = this.presentationProgress;
      boolean settled = progress >= 0.9999F;
      int viewportTop = this.getPaddingTop();
      float viewportHeight = (float)Math.max(1, this.getHeight() - this.getPaddingTop() - this.getPaddingBottom());
      float trackY = (float)this.trackContainer.getTop() + this.trackContainer.getTranslationY();
      float risePx = this.presentationRisePx;

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         if (settled) {
            holder.row.setPresentationTransform(0.0F, 1.0F);
            continue;
         }

         float rowCenter = trackY + (float)(holder.top + holder.bottom) * 0.5F + holder.row.getTranslationY();
         float order = this.clampAlpha((rowCenter - (float)viewportTop) / viewportHeight);
         float local = this.clampAlpha((progress - PRESENTATION_STAGGER * order) / (1.0F - PRESENTATION_STAGGER));
         float rise = presentationRiseCurve(local);
         float fade = this.clampAlpha(local / 0.55F);
         fade = fade * fade * (3.0F - 2.0F * fade);
         holder.row.setPresentationTransform((1.0F - rise) * risePx, fade);
      }
   }

   /** Ease-out with a small overshoot (about 4%): the line lands a touch high and settles. */
   private static float presentationRiseCurve(float t) {
      float u = t - 1.0F;
      return 1.0F + (PRESENTATION_OVERSHOOT + 1.0F) * u * u * u + PRESENTATION_OVERSHOOT * u * u;
   }

   @Override
   public void setVisibility(int visibility) {
      super.setVisibility(visibility);
      if (visibility == GONE && this.presentationTarget >= 0.999F && this.presentationAnimator == null) {
         return;
      }

      if (visibility == GONE) {
         // Hidden for good: the next show starts from lines in place unless a reveal says otherwise.
         this.setPresentationProgress(1.0F);
      }
   }

   public void prepareForRevealFrame(@Nullable Runnable onPrepared) {
      if (this.rowBuildActive) { this.rowBuildOnPrepared=onPrepared;return; }
      if (this.rowHolders.isEmpty()) {
         if (onPrepared != null) {
            onPrepared.run();
         }

      } else if (this.getWidth() > 0 && this.getHeight() > 0) {
         this.stopTrackAnimator();
         this.clearSourceAlphaTransition();
         this.clearRowLagAnimations();
         this.resolveAndApplyActiveSource(true);
         this.updateDynamicRowGeometry();
         int width = Math.max(0, this.getWidth() - this.getPaddingLeft() - this.getPaddingRight());
         int height = Math.max(0, this.getHeight() - this.getPaddingTop() - this.getPaddingBottom());
         if (width > 0 && height > 0) {
            int rowWidthSpec = this.makeRowWidthMeasureSpec(width);

            for(int i = 0; i < this.rowHolders.size(); ++i) {
               RowHolder holder = (RowHolder)this.rowHolders.get(i);
               int rowHeightSpec = this.makeRowHeightMeasureSpec(holder.bottom - holder.top);
               holder.row.measure(rowWidthSpec, rowHeightSpec);
            }

            this.lastRowsMeasureWidthPx = width;
            this.trackContainer.measure(MeasureSpec.makeMeasureSpec(width, 1073741824), MeasureSpec.makeMeasureSpec(Math.max(1, this.contentHeightPx), 1073741824));
            this.layoutDynamicRowsIfPossible();
            this.layoutNonLyricHintIfPossible();
            if (this.trackAnimator == null || !this.trackAnimator.isRunning()) {
               this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
            }
         }

         long renderPositionMs = this.resolveRenderPositionMs();
         this.refreshRowColors();
         this.refreshRowAlphaState(renderPositionMs);
         this.refreshPrepCountdownRows(renderPositionMs);
         this.refreshActiveKaraokeHighlight(renderPositionMs);

         if (onPrepared != null) {
            this.postOnAnimation(onPrepared);
         }

      } else {
         if (onPrepared != null) {
            this.post(onPrepared);
         }

      }
   }

   public void setTranslationVisible(boolean visible, boolean animate) {
      if (this.translationVisible != visible || this.translationToggleAnimator != null) {
         this.translationVisible = visible;
         this.dispatchTranslationStateChangedIfNeeded();
         float targetProgress = visible ? 1.0F : 0.0F;
         if (!this.hasTranslationContent) {
            this.cancelTranslationToggleAnimator();
            this.translationVisibilityProgress = targetProgress;
            this.setAllTranslationRowProgress(targetProgress);
            this.rebuildRows();
            this.resolveAndApplyActiveSource(true);
            this.invalidate();
         } else if (animate && this.isShown() && this.getWidth() > 0 && this.getHeight() > 0 && !this.rowHolders.isEmpty()) {
            this.animateTranslationToggle(targetProgress);
         } else {
            this.cancelTranslationToggleAnimator();
            this.translationVisibilityProgress = targetProgress;
            this.setAllTranslationRowProgress(targetProgress);
            this.rebuildRows();
            this.resolveAndApplyActiveSource(true);
            this.invalidate();
         }
      }
   }

   private void dispatchTranslationStateChangedIfNeeded() {
      if (this.translationStateChangeListener == null) {
         this.lastDispatchedHasTranslationContent = this.hasTranslationContent;
         this.lastDispatchedTranslationVisible = this.translationVisible;
      } else if (this.lastDispatchedHasTranslationContent != this.hasTranslationContent || this.lastDispatchedTranslationVisible != this.translationVisible) {
         this.lastDispatchedHasTranslationContent = this.hasTranslationContent;
         this.lastDispatchedTranslationVisible = this.translationVisible;
         this.translationStateChangeListener.onTranslationStateChanged(this.hasTranslationContent, this.translationVisible);
      }
   }

   private void setAllTranslationRowProgress(float progress) {
      float clamped = this.clampAlpha(progress);
      this.translationGeometryVersion++;

      for(int i = 0; i < this.translationRowProgress.length; ++i) {
         this.translationRowProgress[i] = clamped;
      }

   }

   private float resolveTranslationRowProgressByHolderIndex(int holderIndex) {
      if (holderIndex >= 0 && holderIndex < this.translationIndexByHolder.length) {
         int translationIndex = this.translationIndexByHolder[holderIndex];
         return translationIndex >= 0 && translationIndex < this.translationRowProgress.length ? this.clampAlpha(this.translationRowProgress[translationIndex]) : 0.0F;
      } else {
         return 0.0F;
      }
   }

   protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int measuredWidth = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec);
      int measuredHeight = resolveSize(MeasureSpec.getSize(heightMeasureSpec), heightMeasureSpec);
      this.setMeasuredDimension(measuredWidth, measuredHeight);
      int innerWidth = Math.max(0, measuredWidth - this.getPaddingLeft() - this.getPaddingRight());
      int innerHeight = Math.max(0, measuredHeight - this.getPaddingTop() - this.getPaddingBottom());
      this.updateDynamicRowGeometry();
      if (innerWidth > 0 && innerWidth != this.lastRowsMeasureWidthPx) {
         int rowWidthSpec = this.makeRowWidthMeasureSpec(innerWidth);

         for(int i = 0; i < this.rowHolders.size(); ++i) {
            RowHolder holder = (RowHolder)this.rowHolders.get(i);
            int rowHeightSpec = this.makeRowHeightMeasureSpec(holder.bottom - holder.top);
            holder.row.measure(rowWidthSpec, rowHeightSpec);
         }

         this.lastRowsMeasureWidthPx = innerWidth;
      }

      this.trackContainer.measure(MeasureSpec.makeMeasureSpec(innerWidth, 1073741824), MeasureSpec.makeMeasureSpec(Math.max(1, this.contentHeightPx), 1073741824));
      this.nonLyricHintView.measure(MeasureSpec.makeMeasureSpec(Math.max(1, innerWidth), Integer.MIN_VALUE), MeasureSpec.makeMeasureSpec(Math.max(1, innerHeight), Integer.MIN_VALUE));
      if (innerHeight == 0) {
         this.trackContainer.setTranslationY(0.0F);
      }

   }

   protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int left = this.getPaddingLeft();
      int top = this.getPaddingTop();
      int right = this.getWidth() - this.getPaddingRight();
      int bottom = this.getHeight() - this.getPaddingBottom();
      int width = Math.max(0, right - left);
      int height = Math.max(0, bottom - top);
      if (width <= 0 || width == this.lastWrapWidthPx || this.timedLines.isEmpty() && this.plainLines.isEmpty()) {
         if (width > 0 && width != this.lastRowsMeasureWidthPx) {
            int rowWidthSpec = this.makeRowWidthMeasureSpec(width);

            for(int i = 0; i < this.rowHolders.size(); ++i) {
               RowHolder holder = (RowHolder)this.rowHolders.get(i);
               int rowHeightSpec = this.makeRowHeightMeasureSpec(holder.bottom - holder.top);
               holder.row.measure(rowWidthSpec, rowHeightSpec);
            }

            this.lastRowsMeasureWidthPx = width;
         }

         this.updateDynamicRowGeometry();
         this.trackContainer.measure(MeasureSpec.makeMeasureSpec(width, 1073741824), MeasureSpec.makeMeasureSpec(Math.max(1, this.contentHeightPx), 1073741824));
         this.layoutDynamicRowsIfPossible();
         this.layoutNonLyricHintIfPossible();
         if (!this.manualScrollActive && (this.trackAnimator == null || !this.trackAnimator.isRunning())) {
            this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
         }

         this.refreshRowBlurState();
         this.refreshActiveKaraokeHighlight(this.resolveRenderPositionMs());
         if (height <= 0) {
            this.trackContainer.setTranslationY(0.0F);
         }

         this.refreshRowEdgeFadeState();
      } else {
         this.lastWrapWidthPx = width;
         this.rebuildRows();
      }
   }

   protected void dispatchDraw(@NonNull Canvas canvas) {
      int left = this.getPaddingLeft();
      int top = this.getPaddingTop();
      int right = this.getWidth() - this.getPaddingRight();
      int bottom = this.getHeight() - this.getPaddingBottom();
      if (right > left && bottom > top) {
         int save = canvas.save();
         canvas.clipRect(left, top, right, bottom);
         super.dispatchDraw(canvas);
         canvas.restoreToCount(save);
      }
   }

   private boolean shouldScheduleTimedLyricsFrame() {
      return this.tickerEnabled && this.playbackRunning && !this.progressScrubbingActive && this.isAttachedToWindow() && this.isShown() && this.getWindowVisibility() == 0 && this.getVisibility() == 0 && this.getAlpha() > 0.01F && !this.timedLines.isEmpty();
   }

   private void scheduleTimedLyricsFrameLoopIfNeeded() {
      if (!this.shouldScheduleTimedLyricsFrame()) {
         this.stopTimedLyricsFrameLoop();
      } else {
         long nowUptimeMs = SystemClock.uptimeMillis();
         long delayMs = this.resolveNextTimedLyricsFrameDelayMs(
               this.resolveRenderPositionMs(nowUptimeMs)
         );
         long dueUptimeMs = delayMs <= 0L ? nowUptimeMs : nowUptimeMs + delayMs;
         if (this.timedLyricsFrameLoopActive
               && this.timedLyricsFrameDueUptimeMs <= dueUptimeMs) {
            return;
         }

         if (this.timedLyricsFrameLoopActive) {
            this.removeCallbacks(this.timedLyricsFrameRunnable);
         }

         this.timedLyricsFrameLoopActive = true;
         this.timedLyricsFrameDueUptimeMs = dueUptimeMs;
         if (delayMs <= 0L) {
            this.postOnAnimation(this.timedLyricsFrameRunnable);
         } else {
            this.postDelayed(this.timedLyricsFrameRunnable, delayMs);
         }
      }
   }

   private void stopTimedLyricsFrameLoop() {
      this.removeCallbacks(this.timedLyricsFrameRunnable);
      this.timedLyricsFrameLoopActive = false;
      this.timedLyricsFrameDueUptimeMs = Long.MAX_VALUE;
   }

   private long resolveNextTimedLyricsFrameDelayMs(long renderPositionMs) {
      if (this.shouldRunRealtimeTimedLyricsFrame(renderPositionMs)) {
         return 0L;
      }

      long nextBoundaryMs = this.resolveNextTimedLyricsVisualBoundaryMs(renderPositionMs);
      return LyricsFrameTimingPolicy.resolveWakeDelayMs(
            renderPositionMs,
            nextBoundaryMs,
            this.reportedPlaybackSpeed,
            TIMED_LYRICS_IDLE_WAKE_MS
      );
   }

   private long resolveNextTimedLyricsVisualBoundaryMs(long renderPositionMs) {
      long nextBoundaryMs = Long.MAX_VALUE;

      // Scan from the current visual state as well as future lines. A karaoke line can become the
      // active visual source during pre-scroll while its first-word timestamp is still in the
      // future, so starting at activeSourceIndex + 1 loses the boundary that starts its animation.
      int firstSourceIndex = Math.max(0, this.activeSourceIndex);
      if (this.activeSourceIndex < 0) {
         firstSourceIndex = 0;
      }

      for(int i = firstSourceIndex; i < this.sourceGroups.size(); ++i) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(i);
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               group.timeMs
         );

         if (!group.prepCountdown && i > 0) {
            SourceGroup previous = (SourceGroup)this.sourceGroups.get(i - 1);
            if (!previous.prepCountdown && previous.timeMs >= 0L && group.timeMs > previous.timeMs) {
               long scrollStartMs = group.timeMs - this.resolveKaraokeScrollAdvanceMs(i - 1, i);
               nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
                     renderPositionMs,
                     nextBoundaryMs,
                     scrollStartMs
               );
               if (!this.isKaraokeSource(i - 1)) {
                  long prepareAlphaStartMs = previous.timeMs
                        + (long)Math.round((float)(group.timeMs - previous.timeMs) * 0.5F);
                  nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
                        renderPositionMs,
                        nextBoundaryMs,
                        prepareAlphaStartMs
                  );
               }
            }
         }
      }

      for(int i = 0; i < this.sourceGroups.size(); ++i) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(i);
         if (!group.prepCountdown || group.prepTargetTimeMs <= 0L) {
            continue;
         }

         long prepStartMs = this.resolvePrepCountdownStartMs(
               group.sourceIndex,
               group.prepTargetTimeMs
         );
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               this.resolvePrepRevealStartMs(prepStartMs)
         );
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               prepStartMs
         );
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               group.prepTargetTimeMs
         );
      }

      for(int i = 0; i < this.timedNonLyricLines.size(); ++i) {
         TimedLine line = (TimedLine)this.timedNonLyricLines.get(i);
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               line.timeMs - NON_LYRIC_HINT_EARLY_MS
         );
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               line.timeMs
         );
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               line.timeMs + NON_LYRIC_HINT_SHOW_MS - NON_LYRIC_HINT_FADE_MS
         );
         nextBoundaryMs = LyricsFrameTimingPolicy.includeFutureBoundary(
               renderPositionMs,
               nextBoundaryMs,
               line.timeMs + NON_LYRIC_HINT_SHOW_MS
         );
      }

      return nextBoundaryMs;
   }

   private long resolveCurveSyncedScrollAdvanceMs() {
      float curveInput = this.resolveInterpolatorInputForOutput(this.trackScrollInterpolator, KARAOKE_SCROLL_SYNC_VISUAL_PROGRESS);
      return Math.max(1L, (long)Math.round((float)TRACK_SCROLL_ANIM_MS * curveInput));
   }

   private float resolveInterpolatorInputForOutput(@NonNull Interpolator interpolator, float targetOutput) {
      float target = this.clampAlpha(targetOutput);
      float low = 0.0F;
      float high = 1.0F;

      for(int i = 0; i < KARAOKE_SCROLL_SYNC_SEARCH_STEPS; ++i) {
         float mid = (low + high) * 0.5F;
         float value = this.clampAlpha(interpolator.getInterpolation(mid));
         if (value < target) {
            low = mid;
         } else {
            high = mid;
         }
      }

      return high;
   }

   private boolean shouldRunRealtimeTimedLyricsFrame(long renderPositionMs) {
      if (!this.sourceAlphaTransitionActive && this.trackAnimator == null && this.activeLagSession == null) {
         if (this.hasActiveKaraokeMotionFrame(renderPositionMs) || this.hasRecentKaraokeGlyphLiftFrame(renderPositionMs)) {
            return true;
         } else {
            return this.hasCollapsedKaraokeHandoffFrame(renderPositionMs)
                  || this.hasFinishingKaraokeHandoffFrame(renderPositionMs)
                  || this.hasActivePrepRevealFrame(renderPositionMs)
                  || this.hasActivePrepCountdownFrame(renderPositionMs)
                  || this.hasActiveNonLyricHintFadeFrame(renderPositionMs)
                  || this.isPreparedNextAlphaWindowActive(renderPositionMs);
         }
      } else {
         return true;
      }
   }

   private boolean hasRecentKaraokeGlyphLiftFrame(long renderPositionMs) {
      if (this.activeSourceIndex < 0 || this.sourceGroups.isEmpty()) {
         return false;
      }

      int lastSourceIndex = Math.min(this.activeSourceIndex, this.sourceGroups.size() - 1);
      int firstSourceIndex = Math.max(0, lastSourceIndex - KARAOKE_GLYPH_LIFT_TRAILING_SOURCE_WINDOW);
      for(int sourceIndex = lastSourceIndex; sourceIndex >= firstSourceIndex; --sourceIndex) {
         if (this.hasSourceKaraokeGlyphLiftFrame(sourceIndex, renderPositionMs)) {
            return true;
         }
      }

      return false;
   }

   private boolean hasSourceKaraokeGlyphLiftFrame(int sourceIndex, long renderPositionMs) {
      if (sourceIndex < 0 || sourceIndex >= this.sourceGroups.size()) {
         return false;
      }

      SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
      int firstRowIndex = Math.max(0, group.firstRowIndex);
      int lastRowIndex = Math.min(this.rowHolders.size() - 1, group.lastRowIndex);
      for(int rowIndex = firstRowIndex; rowIndex <= lastRowIndex; ++rowIndex) {
         RowHolder holder = (RowHolder)this.rowHolders.get(rowIndex);
         if (holder.sourceIndex == sourceIndex && holder.row.hasInFlightKaraokeGlyphLift(renderPositionMs)) {
            return true;
         }
      }

      return false;
   }

   private boolean hasActiveKaraokeMotionFrame(long renderPositionMs) {
      if (this.activeSourceIndex >= 0 && this.activeSourceIndex < this.timedLines.size() && this.activeSourceIndex < this.sourceGroups.size()) {
         TimedLine activeLine = (TimedLine)this.timedLines.get(this.activeSourceIndex);
         KaraokeTimeline mainTimeline = activeLine.karaokeTimeline;
         KaraokeTimeline translationTimeline = this.activeSourceIndex < this.timedTranslationTimelines.size() ? (KaraokeTimeline)this.timedTranslationTimelines.get(this.activeSourceIndex) : null;
         long motionEndMs = Math.max(this.resolveKaraokeTimelineMotionEndMs(mainTimeline), this.resolveKaraokeTimelineMotionEndMs(translationTimeline));
         if (motionEndMs == Long.MIN_VALUE) {
            return false;
         } else {
            long motionStartMs = Math.max(0L, ((SourceGroup)this.sourceGroups.get(this.activeSourceIndex)).timeMs);
            return renderPositionMs >= motionStartMs && renderPositionMs < motionEndMs;
         }
      } else {
         return false;
      }
   }

   private boolean hasFinishingKaraokeHandoffFrame(long renderPositionMs) {
      return this.resolveFinishingKaraokeSourceIndex(renderPositionMs) >= 0;
   }

   private boolean hasCollapsedKaraokeHandoffFrame(long renderPositionMs) {
      return this.resolveCollapsedKaraokeSpan(renderPositionMs) != null;
   }

   private int resolveFinishingKaraokeSourceIndex(long renderPositionMs) {
      if (this.activeSourceIndex <= 0 || this.activeSourceIndex >= this.sourceGroups.size()) {
         return -1;
      }

      SourceGroup activeGroup = (SourceGroup)this.sourceGroups.get(this.activeSourceIndex);
      if (activeGroup.prepCountdown || activeGroup.timeMs <= renderPositionMs) {
         return -1;
      }

      int previousIndex = this.activeSourceIndex - 1;
      SourceGroup previousGroup = (SourceGroup)this.sourceGroups.get(previousIndex);
      if (previousGroup.prepCountdown || previousGroup.timeMs < 0L || activeGroup.timeMs <= previousGroup.timeMs) {
         return -1;
      }

      long advanceMs = this.resolveKaraokeScrollAdvanceMs(previousIndex, this.activeSourceIndex);
      if (advanceMs <= 0L || renderPositionMs < activeGroup.timeMs - advanceMs) {
         return -1;
      }

      if (!this.isKaraokeSource(previousIndex)) {
         return -1;
      }

      TimedLine previousLine = previousIndex < this.timedLines.size() ? (TimedLine)this.timedLines.get(previousIndex) : null;
      KaraokeTimeline mainTimeline = previousLine != null ? previousLine.karaokeTimeline : null;
      KaraokeTimeline translationTimeline = previousIndex < this.timedTranslationTimelines.size() ? (KaraokeTimeline)this.timedTranslationTimelines.get(previousIndex) : null;
      long motionEndMs = Math.max(this.resolveKaraokeTimelineMotionEndMs(mainTimeline), this.resolveKaraokeTimelineMotionEndMs(translationTimeline));
      return motionEndMs > renderPositionMs ? previousIndex : -1;
   }

   private void clampTerminalEnd(@Nullable KaraokeTimeline timeline, long nextStartMs) {
      if (timeline != null && timeline.terminalEndMs > 0L && timeline.terminalEndMs >= nextStartMs) {
         int count = timeline.tokenTimeMs.length;
         long lastStartMs = count > 0 ? timeline.tokenTimeMs[count - 1] : Long.MAX_VALUE;
         // Past the next start it would hold this line over the next one: cut, or drop it.
         timeline.terminalEndMs = nextStartMs - 1L > lastStartMs ? nextStartMs - 1L : -1L;
      }
   }

   private long resolveKaraokeTimelineMotionEndMs(@Nullable KaraokeTimeline timeline) {
      if (timeline == null) {
         return Long.MIN_VALUE;
      } else {
         int tokenCount = Math.min(timeline.tokenTimeMs.length, timeline.tokenEndCodePoint.length);
         if (tokenCount <= 0) {
            return Long.MIN_VALUE;
         } else {
            long lastTokenStartMs = timeline.tokenTimeMs[tokenCount - 1];
            if (lastTokenStartMs < 0L) {
               return Long.MIN_VALUE;
            } else {
               return lastTokenStartMs + this.resolveKaraokeTokenDurationMs(timeline, tokenCount - 1, tokenCount);
            }
         }
      }
   }

   private long resolveKaraokeTokenDurationMs(@NonNull KaraokeTimeline timeline, int tokenIndex, int tokenCount) {
      long tokenStartMs = timeline.tokenTimeMs[tokenIndex];
      if (tokenIndex + 1 < tokenCount) {
         long nextStartMs = timeline.tokenTimeMs[tokenIndex + 1];
         if (nextStartMs > tokenStartMs) {
            return nextStartMs - tokenStartMs;
         }
      } else if (timeline.terminalEndMs > tokenStartMs) {
         return timeline.terminalEndMs - tokenStartMs;
      }

      if (tokenIndex > 0) {
         long previousStartMs = timeline.tokenTimeMs[tokenIndex - 1];
         if (tokenStartMs > previousStartMs) {
            return tokenStartMs - previousStartMs;
         }
      }

      return KARAOKE_TERMINAL_SCAN_MS;
   }

   private boolean hasActivePrepCountdownFrame(long renderPositionMs) {
      if (this.introPrepEligible && !this.rowHolders.isEmpty() && this.visualSourceIndex >= 0) {
         for(int i = 0; i < this.rowHolders.size(); ++i) {
            RowHolder holder = (RowHolder)this.rowHolders.get(i);
            if (holder.prepCountdown && !holder.translation && holder.sourceIndex == this.visualSourceIndex) {
               long startMs = this.resolvePrepCountdownStartMs(holder.sourceIndex, holder.prepTargetTimeMs);
               if (this.resolvePrepCountdownProgress(startMs, holder.prepTargetTimeMs, renderPositionMs) >= 0.0F) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private boolean hasActivePrepRevealFrame(long renderPositionMs) {
      if (!this.introPrepEligible || this.sourceGroups.isEmpty()) {
         return false;
      } else {
         for(int i = 0; i < this.sourceGroups.size(); ++i) {
            SourceGroup group = (SourceGroup)this.sourceGroups.get(i);
            if (group.prepCountdown && group.prepTargetTimeMs > 0L) {
               long prepStartMs = this.resolvePrepCountdownStartMs(
                     group.sourceIndex,
                     group.prepTargetTimeMs
               );
               long revealStartMs = this.resolvePrepRevealStartMs(prepStartMs);
               if (LyricsFrameTimingPolicy.isActiveInterval(
                     renderPositionMs,
                     revealStartMs,
                     prepStartMs
               )) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private boolean hasActiveNonLyricHintFadeFrame(long renderPositionMs) {
      for(int i = 0; i < this.timedNonLyricLines.size(); ++i) {
         long lineTimeMs = ((TimedLine)this.timedNonLyricLines.get(i)).timeMs;
         if (LyricsFrameTimingPolicy.isActiveInterval(
               renderPositionMs,
               lineTimeMs - NON_LYRIC_HINT_EARLY_MS,
               lineTimeMs
         ) || LyricsFrameTimingPolicy.isActiveInterval(
               renderPositionMs,
               lineTimeMs + NON_LYRIC_HINT_SHOW_MS - NON_LYRIC_HINT_FADE_MS,
               lineTimeMs + NON_LYRIC_HINT_SHOW_MS
         )) {
            return true;
         }
      }

      return false;
   }

   private boolean isPreparedNextAlphaWindowActive(long renderPositionMs) {
      if (this.visualSourceIndex >= 0 && this.visualSourceIndex + 1 < this.sourceGroups.size()) {
         SourceGroup activeGroup = (SourceGroup)this.sourceGroups.get(this.visualSourceIndex);
         SourceGroup nextGroup = (SourceGroup)this.sourceGroups.get(this.visualSourceIndex + 1);
         if (!activeGroup.prepCountdown && !nextGroup.prepCountdown) {
            if (this.isKaraokeSource(this.visualSourceIndex)) {
               return false;
            } else {
               long activeStamp = activeGroup.timeMs;
               long nextStamp = nextGroup.timeMs;
               if (activeStamp >= 0L && nextStamp > activeStamp) {
                  long windowStart = activeStamp + (long)Math.round((float)(nextStamp - activeStamp) * 0.5F);
                  return renderPositionMs > windowStart && renderPositionMs < nextStamp;
               } else {
                  return false;
               }
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private void beginManualScrollPause() {
      this.cancelScheduledManualScrollResume();
      this.stopManualScrollFling();
      this.manualScrollActive = true;
      this.syncPassedReveal();
      if (!this.manualScrollNoBlur) {
         this.manualScrollNoBlur = true;
         this.refreshRowColors();
         this.refreshRowBlurState();
      }

      if (this.browseHandoffAnimator == null && this.sourceAlphaTransitionActive && this.trackAnimator != null && this.trackAnimator.isRunning()) {
         // Grabbed mid line change: the track stops here, the highlight finishes its handover.
         int targetSource = this.sourceAlphaTransitionTo;
         float progress = this.sourceAlphaTransitionProgress;
         this.stopTrackAnimatorForVisualHandoff();
         this.resetTrackScrollMotionSample();
         this.runBrowseHandoffAnimator(targetSource, progress);
      } else {
         this.stopTrackAnimator();
      }

      this.clearRowLagAnimations();
      this.applyFocusDrivenGeometry(true, this.resolveSettledSourceIndex(), true);
      this.scheduleTimedLyricsFrameLoopIfNeeded();
   }

   /**
    * The sung line changes while the lyrics are browsed: its highlight hands over the way it does
    * on a line change (same duration and curve), only the track stays where the user left it.
    */
   private void startBrowseHandoff(int targetSource, long renderPositionMs, boolean immediate) {
      this.finishBrowseHandoff();
      int fromSource = this.visualSourceIndex;
      boolean animate = !immediate && fromSource >= 0 && targetSource >= 0 && this.isAttachedToWindow() && this.getVisibility() == VISIBLE && this.getAlpha() > 0.01F;
      if (!animate) {
         this.clearSourceAlphaTransition();
         this.commitVisualSourceIndex(targetSource);
         return;
      }

      float outgoingStartAlpha = this.resolveCurrentDisplayedMainRowAlpha(fromSource, renderPositionMs);
      float incomingStartAlpha = this.resolveSourceTransitionIncomingStartAlpha(targetSource, renderPositionMs);
      this.beginSourceAlphaTransition(fromSource, targetSource, outgoingStartAlpha, incomingStartAlpha);
      this.runBrowseHandoffAnimator(targetSource, 0.0F);
   }

   private void runBrowseHandoffAnimator(final int targetSource, float startProgress) {
      final float start = this.clampAlpha(startProgress);
      final ValueAnimator animator = com.lab.hv.RustValueAnimator.ofFloat(0.0F, 1.0F);
      this.browseHandoffAnimator = animator;
      this.browseHandoffTargetSource = targetSource;
      animator.setDuration(Math.max(1L, Math.round((double)TRACK_SCROLL_ANIM_MS * (1.0 - start))));
      animator.setInterpolator(this.trackScrollInterpolator);
      animator.addUpdateListener((a) -> {
         if (this.browseHandoffAnimator != animator) return;
         this.updateSourceAlphaTransitionProgress(start + (1.0F - start) * a.getAnimatedFraction());
         long renderPositionMs = this.resolveRenderPositionMs();
         this.refreshRowAlphaState(renderPositionMs);
         this.refreshPrepCountdownRows(renderPositionMs);
      });
      animator.addListener(new AnimatorListenerAdapter() {
         public void onAnimationEnd(Animator animation) {
            if (NowPlayingLyricsTickerView.this.browseHandoffAnimator == animator) {
               NowPlayingLyricsTickerView.this.browseHandoffAnimator = null;
               NowPlayingLyricsTickerView.this.completeBrowseHandoff(targetSource);
            }
         }
      });
      animator.start();
   }

   /** Ends a running browse handover at once, the new line fully highlighted. */
   private void finishBrowseHandoff() {
      ValueAnimator animator = this.browseHandoffAnimator;
      if (animator == null) return;
      this.browseHandoffAnimator = null;
      animator.cancel();
      this.completeBrowseHandoff(this.browseHandoffTargetSource);
   }

   /** Stops driving a browse handover but leaves it where it is, for the track animator to carry on. */
   private void detachBrowseHandoff() {
      ValueAnimator animator = this.browseHandoffAnimator;
      if (animator == null) return;
      this.browseHandoffAnimator = null;
      animator.cancel();
   }

   private void completeBrowseHandoff(int targetSource) {
      this.updateSourceAlphaTransitionProgress(1.0F);
      this.commitVisualSourceIndex(targetSource);
      this.clearSourceAlphaTransition();
      long renderPositionMs = this.resolveRenderPositionMs();
      this.refreshRowAlphaState(renderPositionMs);
      this.refreshPrepCountdownRows(renderPositionMs);
   }

   /** The line at the middle of the view (or the first one below it). */
   private int resolveBrowseAnchorSourceIndex() {
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      if (innerHeight <= 0) return -1;
      float trackY = (float)innerHeight * 0.5F - this.trackContainer.getTranslationY();
      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         if ((float)holder.bottom >= trackY) {
            return holder.sourceIndex;
         }
      }

      return -1;
   }

   private void scheduleManualScrollResume() {
      if (!this.manualScrollActive || this.manualTouchDown || this.manualScrollDragging || this.manualScrollFlinging || !this.tickerEnabled || !this.playbackRunning || this.progressScrubbingActive || !this.isAttachedToWindow()) {
         return;
      }

      if (!this.manualScrollResumePending) {
         this.manualScrollResumePending = true;
         this.postDelayed(this.manualScrollResumeRunnable, MANUAL_SCROLL_RESUME_DELAY_MS);
      }
   }

   private void cancelScheduledManualScrollResume() {
      this.removeCallbacks(this.manualScrollResumeRunnable);
      this.manualScrollResumePending = false;
   }

   private void finishManualScrollPause() {
      this.cancelScheduledManualScrollResume();
      if (!this.tickerEnabled || !this.playbackRunning || this.progressScrubbingActive) {
         this.manualScrollDragging = false;
         this.updateManualPressedSourceIndex(-1, true);
         return;
      }

      if (this.manualScrollActive || this.manualScrollNoBlur) {
         this.manualScrollActive = false;
         this.syncPassedReveal();
         this.manualScrollDragging = false;
         this.updateManualPressedSourceIndex(-1, true);
         this.manualScrollNoBlur = false;
         this.refreshRowColors();
         long renderPositionMs = this.resolveRenderPositionMs();
         int targetSource = this.resolveCurrentSourceIndex(renderPositionMs);
         boolean carryHandoff = this.browseHandoffAnimator != null && this.sourceAlphaTransitionActive && targetSource >= 0 && this.browseHandoffTargetSource == targetSource && this.sourceAlphaTransitionTo == targetSource;
         if (carryHandoff) {
            this.detachBrowseHandoff();
         } else {
            this.finishBrowseHandoff();
         }

         if (targetSource >= 0) {
            this.activeSourceIndex = targetSource;
            if (!carryHandoff) {
               this.clearSourceAlphaTransition();
            }
            this.clearRowLagAnimations();
            float startY = this.trackContainer.getTranslationY();
            float targetY = this.resolveTrackTranslationY(targetSource);
            this.startTrackAnimatorTo(startY, targetY, (LagSession)null, targetSource);
         } else {
            this.resolveAndApplyActiveSource(false);
         }

         this.scheduleTimedLyricsFrameLoopIfNeeded();
      }
   }

   private void refreshPrepCountdownRows(long renderPositionMs) {
      this.refreshNonLyricHint(renderPositionMs);
      if (!this.rowHolders.isEmpty()) {
         boolean needsFrame = false;
         boolean stabilizeVisualProgress = this.shouldStabilizeTimedVisualProgress();

         for(int i = 0; i < this.rowHolders.size(); ++i) {
            RowHolder holder = (RowHolder)this.rowHolders.get(i);
            if (holder.prepCountdown && !holder.translation) {
               if (holder.sourceIndex != this.visualSourceIndex) {
                  holder.row.applyPrepDotsProgress(-1.0F, -1.0F, 0.0F, false);
               } else {
                     long startMs = this.resolvePrepCountdownStartMs(holder.sourceIndex, holder.prepTargetTimeMs);
                     if (startMs < 0L) {
                        holder.row.applyPrepDotsProgress(-1.0F, -1.0F, 0.0F, false);
                     } else {
                        float progress = this.resolvePrepCountdownProgress(startMs, holder.prepTargetTimeMs, renderPositionMs);
                        if (progress < 0.0F) {
                           if (renderPositionMs >= holder.prepTargetTimeMs) {
                              holder.row.applyPrepDotsProgress(1.0F, 1.0F, 1.0F, false);
                           } else {
                              holder.row.applyPrepDotsProgress(-1.0F, -1.0F, 0.0F, false);
                           }
                        } else {
                           long totalDurationMs = Math.max(1L, holder.prepTargetTimeMs - startMs);
                           long finalWindowMs = Math.min(1600L, Math.max(900L, totalDurationMs / 3L));
                           long finalWindowStartMs = holder.prepTargetTimeMs - finalWindowMs;
                           float finalProgress = renderPositionMs >= finalWindowStartMs ? this.clampAlpha((float)(renderPositionMs - finalWindowStartMs) / (float)Math.max(1L, finalWindowMs)) : -1.0F;
                           float finalStageStartProgress = this.clampAlpha((float)(totalDurationMs - finalWindowMs) / (float)totalDurationMs);
                           float sequenceProgress;
                           if (finalProgress >= 0.0F) {
                              sequenceProgress = 1.0F;
                           } else if (finalStageStartProgress <= 0.001F) {
                              sequenceProgress = 1.0F;
                           } else {
                              sequenceProgress = this.clampAlpha(progress / finalStageStartProgress);
                           }

                           holder.row.applyPrepDotsProgress(progress, finalProgress, sequenceProgress, stabilizeVisualProgress);
                           if (renderPositionMs < holder.prepTargetTimeMs) {
                              needsFrame = true;
                           }
                        }
                  }
               }
            }
         }

         if (needsFrame) {
            if (!this.timedLyricsFrameDispatching) {
               this.scheduleTimedLyricsFrameLoopIfNeeded();
               this.postInvalidateOnAnimation();
            }
         }

      }
   }

   public boolean onTouchEvent(MotionEvent event) {
      if (!this.shouldHandleLyricsTouch()) {
         return super.onTouchEvent(event);
      } else {
         this.trackManualVelocity(event);
         switch (event.getActionMasked()) {
            case 0:
               this.manualTouchDown = true;
               this.stopManualScrollFling();
               this.cancelScheduledManualScrollResume();
               this.manualDownX = event.getX();
               this.manualDownY = event.getY();
               this.manualLastY = this.manualDownY;
               this.manualDownTrackY = this.trackContainer.getTranslationY();
               this.manualScrollDragging = false;
               this.updateManualPressedSourceIndex(this.resolveSourceIndexAtLocalY(event.getY()), true);
               if (this.getParent() != null) {
                  this.getParent().requestDisallowInterceptTouchEvent(true);
               }

               return true;
            case 1:
               this.manualTouchDown = false;
               if (this.getParent() != null) {
                  this.getParent().requestDisallowInterceptTouchEvent(false);
               }

               boolean click = !this.manualScrollDragging && Math.abs(event.getX() - this.manualDownX) <= this.clickMoveCancelPx && Math.abs(event.getY() - this.manualDownY) <= this.clickMoveCancelPx && Math.abs(this.trackContainer.getTranslationY() - this.manualDownTrackY) < 0.5F;
               if (click) {
                  this.recycleManualVelocityTracker();
                  this.updateManualPressedSourceIndex(-1, true);
                  this.finishManualClickTouch();
                  this.performClick();
                  this.dispatchLyricLineClick(event.getY());
                  return true;
               }

               this.updateManualPressedSourceIndex(-1, true);
               float velocityY = this.resolveManualScrollVelocityY();
               this.recycleManualVelocityTracker();
               this.manualScrollDragging = false;
               if (!this.startManualScrollFling(velocityY)) {
                  this.scheduleManualScrollResume();
               }
               return true;
            case 2:
               float y = event.getY();
               float dyFromDown = y - this.manualDownY;
               float dxFromDown = event.getX() - this.manualDownX;
               boolean dragStarted = false;
               if (!this.manualScrollDragging && Math.hypot((double)dxFromDown, (double)dyFromDown) > (double)this.touchSlopPx) {
                  this.manualScrollDragging = true;
                  dragStarted = true;
                  this.updateManualPressedSourceIndex(-1, true);
                  this.beginManualScrollPause();
                  this.manualLastY = y;
               } else if (!this.manualScrollDragging) {
                  this.updateManualPressedSourceIndex(this.resolveSourceIndexAtLocalY(y), true);
               }

               if (this.manualScrollDragging) {
                  float dy = dragStarted ? 0.0F : y - this.manualLastY;
                  this.manualLastY = y;
                  if (Math.abs(dy) > 1.0E-4F) {
                     this.trackContainer.setTranslationY(this.clampTrackTranslationY(this.trackContainer.getTranslationY() + dy));
                     this.refreshRowEdgeFadeState();
                     this.invalidate();
                  }
               }

               return true;
            case 3:
               this.manualTouchDown = false;
               if (this.getParent() != null) {
                  this.getParent().requestDisallowInterceptTouchEvent(false);
               }

               this.updateManualPressedSourceIndex(-1, true);
               this.recycleManualVelocityTracker();
               this.manualScrollDragging = false;
               this.scheduleManualScrollResume();
               return true;
            default:
               return true;
         }
      }
   }

   /**
    * A mouse wheel (or a touchpad scrolling as one) browses the lyrics as a drag does: the
    * ticker pauses on the first notch and goes back to the playing line after the same delay.
    */
   @Override
   public boolean onGenericMotionEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_SCROLL
            && event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
            && this.shouldHandleLyricsTouch()) {
         float notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
         if (Math.abs(notches) > 1.0E-4F) {
            this.stopManualScrollFling();
            this.cancelScheduledManualScrollResume();
            if (!this.manualScrollActive) this.beginManualScrollPause();
            float step = ViewConfiguration.get(this.getContext()).getScaledVerticalScrollFactor();
            // Wheel up shows earlier lines: the track moves down.
            this.trackContainer.setTranslationY(this.clampTrackTranslationY(
                  this.trackContainer.getTranslationY() + notches * step));
            this.refreshRowEdgeFadeState();
            this.invalidate();
            this.scheduleManualScrollResume();
            return true;
         }
      }
      return super.onGenericMotionEvent(event);
   }

   private void trackManualVelocity(@NonNull MotionEvent event) {
      if (event.getActionMasked() == 0) {
         this.recycleManualVelocityTracker();
         this.manualVelocityTracker = VelocityTracker.obtain();
      }

      if (this.manualVelocityTracker != null) {
         this.manualVelocityTracker.addMovement(event);
      }
   }

   private float resolveManualScrollVelocityY() {
      if (this.manualVelocityTracker == null) {
         return 0.0F;
      } else {
         try {
            this.manualVelocityTracker.computeCurrentVelocity(1000, (float)this.maxFlingVelocityPx);
            return this.manualVelocityTracker.getYVelocity();
         } catch (Throwable var2) {
            return 0.0F;
         }
      }
   }

   private void recycleManualVelocityTracker() {
      if (this.manualVelocityTracker != null) {
         try {
            this.manualVelocityTracker.recycle();
         } catch (Throwable var2) {
         }

         this.manualVelocityTracker = null;
      }
   }

   private boolean startManualScrollFling(float velocityY) {
      if (!this.manualScrollActive || Math.abs(velocityY) < (float)this.minFlingVelocityPx) {
         return false;
      } else {
         int startY = Math.round(this.trackContainer.getTranslationY());
         int minY = Math.round(this.resolveMinTrackTranslationY());
         int maxY = Math.round(this.resolveMaxTrackTranslationY());
         if (minY > maxY) {
            int tmp = minY;
            minY = maxY;
            maxY = tmp;
         }

         if (minY == maxY) {
            return false;
         } else {
            this.cancelScheduledManualScrollResume();
            this.manualScrollFlinging = true;
            this.manualScrollScroller.fling(0, startY, 0, Math.round(velocityY), 0, 0, minY, maxY);
            this.postOnAnimation(this.manualScrollFlingRunnable);
            return true;
         }
      }
   }

   private void runManualScrollFlingFrame() {
      if (!this.manualScrollFlinging) {
         return;
      }

      if (this.manualScrollScroller.computeScrollOffset()) {
         this.trackContainer.setTranslationY(this.clampTrackTranslationY((float)this.manualScrollScroller.getCurrY()));
         this.refreshRowEdgeFadeState();
         this.invalidate();
         this.postOnAnimation(this.manualScrollFlingRunnable);
      } else {
         this.manualScrollFlinging = false;
         this.scheduleManualScrollResume();
      }
   }

   private void stopManualScrollFling() {
      this.removeCallbacks(this.manualScrollFlingRunnable);
      if (!this.manualScrollScroller.isFinished()) {
         this.manualScrollScroller.abortAnimation();
      }

      this.manualScrollFlinging = false;
   }

   private float resolveMinTrackTranslationY() {
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      float contentHeight = (float)Math.max(1, this.contentHeightPx);
      if (innerHeight <= 0) {
         return 0.0F;
      } else {
         return contentHeight <= (float)innerHeight ? ((float)innerHeight - contentHeight) * 0.5F : this.resolveScrollableMinY(innerHeight, contentHeight);
      }
   }

   private float resolveMaxTrackTranslationY() {
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      float contentHeight = (float)Math.max(1, this.contentHeightPx);
      if (innerHeight <= 0) {
         return 0.0F;
      } else {
         return contentHeight <= (float)innerHeight ? ((float)innerHeight - contentHeight) * 0.5F : this.resolveScrollableMaxY(innerHeight);
      }
   }

   private void finishManualClickTouch() {
      this.finishBrowseHandoff();
      this.cancelScheduledManualScrollResume();
      this.stopManualScrollFling();
      this.recycleManualVelocityTracker();
      this.manualScrollActive = false;
      this.syncPassedReveal();
      this.manualScrollDragging = false;
      this.manualPressedSourceIndex = -1;
      if (this.manualScrollNoBlur) {
         this.manualScrollNoBlur = false;
         this.refreshRowColors();
      }

      this.applyFocusDrivenGeometry(true, this.resolveSettledSourceIndex(), true);
      this.scheduleTimedLyricsFrameLoopIfNeeded();
   }

   public boolean performClick() {
      return super.performClick();
   }

   private void dispatchLyricLineClick(float localY) {
      if (this.lyricSeekListener != null && !this.sourceGroups.isEmpty()) {
         int holderIndex = this.findRowHolderIndexAtLocalY(localY);
         if (holderIndex >= 0 && holderIndex < this.rowHolders.size()) {
            RowHolder holder = (RowHolder)this.rowHolders.get(holderIndex);
            long seekMs = this.resolveSeekTimeForSource(holder.sourceIndex);
            if (seekMs >= 0L) {
               this.lyricSeekListener.onLyricSeekRequested(Math.max(0L, seekMs + this.lyricsDelayMs));
            }
         }
      }
   }

   private void updateManualPressedSourceIndex(int sourceIndex, boolean animate) {
      int safeSourceIndex = sourceIndex >= 0 && sourceIndex < this.sourceGroups.size() ? sourceIndex : -1;
      if (this.manualPressedSourceIndex != safeSourceIndex) {
         this.manualPressedSourceIndex = safeSourceIndex;
         this.animatePressedFeedbackToSource(safeSourceIndex, animate);
      }
   }

   private void animatePressedFeedbackToSource(int sourceIndex, boolean animate) {
      this.cancelPressedFeedbackAnimator();
      int previousSourceIndex = this.pressedFeedbackSourceIndex;
      if (sourceIndex < 0) {
         if (this.pressedFeedbackSourceIndex >= 0 && !(this.pressedFeedbackProgress <= 0.001F)) {
            int releaseSourceIndex = this.pressedFeedbackSourceIndex;
            if (!animate) {
               this.setSourceGroupPressedProgress(releaseSourceIndex, 0.0F);
               this.pressedFeedbackSourceIndex = -1;
               this.pressedFeedbackProgress = 0.0F;
               this.refreshPressedFeedbackVisuals();
            } else {
               this.startPressedFeedbackAnimator(releaseSourceIndex, this.pressedFeedbackProgress, 0.0F, 170L, true);
            }
         } else {
            this.pressedFeedbackSourceIndex = -1;
            this.pressedFeedbackProgress = 0.0F;
            this.refreshPressedFeedbackVisuals();
         }
      } else {
         if (this.pressedFeedbackSourceIndex >= 0 && this.pressedFeedbackSourceIndex != sourceIndex) {
            this.setSourceGroupPressedProgress(this.pressedFeedbackSourceIndex, 0.0F);
         }

         this.pressedFeedbackSourceIndex = sourceIndex;
         if (!animate) {
            this.pressedFeedbackProgress = 1.0F;
            this.refreshPressedFeedbackVisuals();
         } else {
            float startProgress = previousSourceIndex == sourceIndex ? this.pressedFeedbackProgress : 0.0F;
            if (startProgress <= 0.001F) {
               this.setSourceGroupPressedProgress(sourceIndex, 0.0F);
            }

            this.startPressedFeedbackAnimator(sourceIndex, startProgress, 1.0F, 135L, false);
         }
      }
   }

   private void startPressedFeedbackAnimator(final int sourceIndex, float start, float end, long durationMs, final boolean clearOnEnd) {
      this.pressedFeedbackSourceIndex = sourceIndex;
      this.pressedFeedbackAnimator = com.lab.hv.RustValueAnimator.ofFloat(new float[]{start, end});
      this.pressedFeedbackAnimator.setDuration(durationMs);
      this.pressedFeedbackAnimator.setInterpolator(BezierControl.getBezierInterpolator(2));
      this.pressedFeedbackAnimator.addUpdateListener((animation) -> {
         if (this.pressedFeedbackSourceIndex == sourceIndex) {
            this.pressedFeedbackProgress = (Float)animation.getAnimatedValue();
            this.refreshPressedFeedbackVisuals();
         }
      });
      this.pressedFeedbackAnimator.addListener(new AnimatorListenerAdapter() {
         public void onAnimationEnd(Animator animation) {
            NowPlayingLyricsTickerView.this.finishPressedFeedbackAnimation(sourceIndex, clearOnEnd);
         }

         public void onAnimationCancel(Animator animation) {
            NowPlayingLyricsTickerView.this.finishPressedFeedbackAnimation(sourceIndex, clearOnEnd);
         }
      });
      this.pressedFeedbackAnimator.start();
   }

   private void finishPressedFeedbackAnimation(int sourceIndex, boolean clearOnEnd) {
      if (this.pressedFeedbackAnimator != null) {
         this.pressedFeedbackAnimator = null;
      }

      if (this.pressedFeedbackSourceIndex == sourceIndex) {
         if (clearOnEnd) {
            this.setSourceGroupPressedProgress(sourceIndex, 0.0F);
            this.pressedFeedbackSourceIndex = -1;
            this.pressedFeedbackProgress = 0.0F;
            this.refreshPressedFeedbackVisuals();
         } else {
            this.pressedFeedbackProgress = 1.0F;
            this.refreshPressedFeedbackVisuals();
         }
      }
   }

   private void cancelPressedFeedbackAnimator() {
      if (this.pressedFeedbackAnimator != null) {
         this.pressedFeedbackAnimator.cancel();
         this.pressedFeedbackAnimator = null;
      }

   }

   private void refreshPressedFeedbackVisuals() {
      if (this.pressedFeedbackSourceIndex >= 0 && this.pressedFeedbackSourceIndex < this.sourceGroups.size() && !(this.pressedFeedbackProgress <= 0.001F) && this.trackContainer.getWidth() > 0) {
         this.setSourceGroupPressedProgress(this.pressedFeedbackSourceIndex, this.pressedFeedbackProgress);
         this.layoutPressedRowCardForSource(this.pressedFeedbackSourceIndex, this.pressedFeedbackProgress);
      } else {
         this.pressedRowCardView.setVisibility(4);
         this.pressedRowCardView.setAlpha(0.0F);
         this.pressedRowCardView.setTranslationY(0.0F);
      }
   }

   private void layoutPressedRowCardForSource(int sourceIndex, float progress) {
      if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
         float contentLeft = Float.POSITIVE_INFINITY;
         float contentRight = Float.NEGATIVE_INFINITY;
         int groupTop = Integer.MAX_VALUE;
         int groupBottom = Integer.MIN_VALUE;

         for(int holderIndex = group.firstRowIndex; holderIndex <= group.lastRowIndex; ++holderIndex) {
            RowHolder holder = (RowHolder)this.rowHolders.get(holderIndex);
            contentLeft = Math.min(contentLeft, holder.row.getContentVisualLeftPx());
            contentRight = Math.max(contentRight, holder.row.getContentVisualRightPx());
            groupTop = Math.min(groupTop, holder.top);
            groupBottom = Math.max(groupBottom, holder.bottom);
         }

         if (Float.isFinite(contentLeft) && Float.isFinite(contentRight) && groupBottom > groupTop) {
            int trackWidth = this.trackContainer.getWidth();
            int cardLeft = Math.max(0, Math.round(contentLeft - this.touchPressCardHorizontalPadPx - this.touchPressCardContentMarginPx));
            int cardRight = Math.min(trackWidth, Math.round(contentRight + this.touchPressCardHorizontalPadPx + this.touchPressCardContentMarginPx));
            int cardTop = Math.max(0, Math.round((float)groupTop - this.touchPressCardTopPadPx - this.touchPressCardContentMarginPx));
            int cardBottom = Math.min(Math.max(this.contentHeightPx, groupBottom), Math.round((float)groupBottom + this.touchPressCardBottomPadPx + this.touchPressCardContentMarginPx));
            if (cardRight > cardLeft && cardBottom > cardTop) {
               if (this.pressedRowCardView.getParent() != this.trackContainer) {
                  this.trackContainer.addView(this.pressedRowCardView, 0, new FrameLayout.LayoutParams(0, 0));
               }

               this.pressedRowCardView.layout(cardLeft, cardTop, cardRight, cardBottom);
               this.pressedRowCardView.setVisibility(0);
               this.pressedRowCardView.setAlpha(this.clampAlpha(progress));
               this.pressedRowCardView.setTranslationY(this.touchPressRowSinkPx * this.clampAlpha(progress));
               this.pressedRowCardView.invalidate();
            } else {
               this.pressedRowCardView.setVisibility(4);
               this.pressedRowCardView.setAlpha(0.0F);
               this.pressedRowCardView.setTranslationY(0.0F);
            }
         } else {
            this.pressedRowCardView.setVisibility(4);
            this.pressedRowCardView.setAlpha(0.0F);
            this.pressedRowCardView.setTranslationY(0.0F);
         }
      } else {
         this.pressedRowCardView.setVisibility(4);
         this.pressedRowCardView.setAlpha(0.0F);
         this.pressedRowCardView.setTranslationY(0.0F);
      }
   }

   private void setSourceGroupPressedProgress(int sourceIndex, float progress) {
      if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
         float safeProgress = this.clampAlpha(progress);

         for(int holderIndex = group.firstRowIndex; holderIndex <= group.lastRowIndex; ++holderIndex) {
            ((RowHolder)this.rowHolders.get(holderIndex)).row.setPressedFeedbackProgress(safeProgress);
         }

      }
   }

   private int resolveSourceIndexAtLocalY(float localY) {
      int holderIndex = this.findRowHolderIndexAtLocalY(localY);
      return holderIndex >= 0 && holderIndex < this.rowHolders.size() ? ((RowHolder)this.rowHolders.get(holderIndex)).sourceIndex : -1;
   }

   private int findRowHolderIndexAtLocalY(float localY) {
      float trackY = localY - (float)this.getPaddingTop() - this.trackContainer.getTranslationY();

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         if (trackY >= (float)holder.top && trackY <= (float)holder.bottom) {
            return i;
         }
      }

      return -1;
   }

   private long resolveSeekTimeForSource(int sourceIndex) {
      if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
         return group.prepCountdown && group.prepTargetTimeMs > 0L ? group.prepTargetTimeMs : Math.max(0L, group.timeMs);
      } else {
         return -1L;
      }
   }

   private long resolvePrepCountdownStartMs(int sourceIndex, long targetTimeMs) {
      if (targetTimeMs > 0L && sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
         long startMs = ((SourceGroup)this.sourceGroups.get(sourceIndex)).timeMs;
         if (startMs >= targetTimeMs) {
            startMs = Math.max(0L, targetTimeMs - 4000L);
         }

         return startMs;
      } else {
         return -1L;
      }
   }

   private float resolvePrepCountdownProgress(long startMs, long targetTimeMs, long renderPositionMs) {
      if (targetTimeMs > 0L && startMs >= 0L && startMs < targetTimeMs) {
         return renderPositionMs >= startMs && renderPositionMs < targetTimeMs ? this.clampAlpha((float)(renderPositionMs - startMs) / (float)Math.max(1L, targetTimeMs - startMs)) : -1.0F;
      } else {
         return -1.0F;
      }
   }

   private long resolvePrepRevealStartMs(long prepStartMs) {
      return prepStartMs <= 0L ? 0L : Math.max(0L, prepStartMs - PREP_REVEAL_LEAD_MS);
   }

   private float resolvePrepRevealProgress(int sourceIndex, long targetTimeMs, long renderPositionMs) {
      long startMs = this.resolvePrepCountdownStartMs(sourceIndex, targetTimeMs);
      if (startMs < 0L) {
         return 1.0F;
      } else if (startMs <= 0L || renderPositionMs >= startMs) {
         return 1.0F;
      } else {
         long revealStartMs = this.resolvePrepRevealStartMs(startMs);
         if (renderPositionMs <= revealStartMs) {
            return 0.0F;
         } else {
            float t = this.clampAlpha((float)(renderPositionMs - revealStartMs) / (float)Math.max(1L, startMs - revealStartMs));
            return this.trackScrollInterpolator.getInterpolation(t);
         }
      }
   }

   private float resolveStablePrepRevealProgress(@NonNull SourceGroup group, long renderPositionMs) {
      float progress = this.resolvePrepRevealProgress(group.sourceIndex, group.prepTargetTimeMs, renderPositionMs);
      if (!group.prepCountdown || !this.shouldStabilizeTimedVisualProgress()) {
         group.prepRevealVisualProgress = progress;
         return progress;
      }

      float stableProgress = Float.isNaN(group.prepRevealVisualProgress) ? progress : Math.max(group.prepRevealVisualProgress, progress);
      group.prepRevealVisualProgress = stableProgress;
      return stableProgress;
   }

   private boolean shouldStabilizeTimedVisualProgress() {
      return this.tickerEnabled && this.playbackRunning && !this.progressScrubbingActive;
   }

   private void clearTimedVisualProgressState() {
      for(int i = 0; i < this.sourceGroups.size(); ++i) {
         ((SourceGroup)this.sourceGroups.get(i)).prepRevealVisualProgress = Float.NaN;
      }

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         if (holder.prepCountdown) {
            holder.row.clearPrepDotsProgressState();
         }
      }

      this.appliedFocusGeometrySignature = Long.MIN_VALUE;
   }

   private void resolveAndApplyActiveSource(boolean immediate) {
      long renderPositionMs = this.resolveRenderPositionMs();
      int resolved = this.resolveCurrentSourceIndex(renderPositionMs);
      if (this.manualScrollActive) {
         int shownSourceIndex = this.browseHandoffAnimator != null ? this.browseHandoffTargetSource : this.visualSourceIndex;
         this.activeSourceIndex = resolved;
         if (resolved != shownSourceIndex) {
            this.startBrowseHandoff(resolved, renderPositionMs, immediate);
         }

         this.refreshRowAlphaState(renderPositionMs);
         this.refreshPrepCountdownRows(renderPositionMs);
      } else if (resolved < 0) {
         this.stopTrackAnimator();
         this.activeSourceIndex = -1;
         this.commitVisualSourceIndex(-1);
         this.clearSourceAlphaTransition();
         this.applyFocusDrivenGeometry(false, -1, true);
         this.refreshRowAlphaState(renderPositionMs);
         this.refreshPrepCountdownRows(renderPositionMs);
         this.clearRowLagAnimations();
         this.trackContainer.setTranslationY(this.resolveTrackTranslationY(-1));
      } else {
         int previousSourceIndex = this.activeSourceIndex;
         int previousVisualSourceIndex = this.resolveTransitionHandoffSourceIndex();
         boolean changed = resolved != previousSourceIndex;
         if (!changed) {
            // refreshRowAlphaState starts with this same geometry check.
            this.refreshRowAlphaState(renderPositionMs);
            this.refreshPrepCountdownRows(renderPositionMs);
            if (this.getWidth() != 0 && this.getHeight() != 0) {
               if (this.trackAnimator == null || !this.trackAnimator.isRunning()) {
                  this.trackContainer.setTranslationY(this.resolveTrackTranslationY(previousVisualSourceIndex));
               }

            } else {
               this.requestLayout();
            }
         } else {
            boolean shouldAnimate = !immediate && this.tickerEnabled && this.getVisibility() == 0 && this.getAlpha() > 0.01F;
            float outgoingStartAlpha = shouldAnimate ? this.resolveCurrentDisplayedMainRowAlpha(previousVisualSourceIndex, renderPositionMs) : 1.0F;
            float incomingStartAlpha = shouldAnimate ? this.resolveSourceTransitionIncomingStartAlpha(resolved, renderPositionMs) : 1.0F;
            // A running animator is intentionally left alive until startTrackAnimatorTo().
            // That method samples and carries its velocity into the new target.
            if (!shouldAnimate || this.trackAnimator == null || !this.trackAnimator.isRunning()) {
               this.stopTrackAnimator();
            }

            this.activeSourceIndex = resolved;
            if (this.getWidth() != 0 && this.getHeight() != 0) {
               if (!shouldAnimate) {
                  this.clearSourceAlphaTransition();
                  this.clearRowLagAnimations();
                  this.commitVisualSourceIndex(resolved);
                  this.applyFocusDrivenGeometry(false, -1, true);
                  this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
                  this.refreshRowAlphaState(renderPositionMs);
                  this.refreshPrepCountdownRows(renderPositionMs);
               } else {
                  this.beginSourceAlphaTransition(previousVisualSourceIndex, resolved, outgoingStartAlpha, incomingStartAlpha);
                  this.applyFocusDrivenGeometry(false, -1, true);
                  this.refreshRowAlphaState(renderPositionMs);
                  this.refreshPrepCountdownRows(renderPositionMs);
                  float startTrackY = this.trackContainer.getTranslationY();
                  float targetTrackY = this.resolveTrackTranslationY(resolved);
                  LagSession lagSession = this.buildDirectionalLagSession(previousVisualSourceIndex, resolved, startTrackY, targetTrackY);
                  this.startTrackAnimatorTo(startTrackY, targetTrackY, lagSession, resolved, previousVisualSourceIndex);
               }
            } else {
               this.commitVisualSourceIndex(resolved);
               this.applyFocusDrivenGeometry(false, -1, true);
               this.refreshRowAlphaState(renderPositionMs);
               this.refreshPrepCountdownRows(renderPositionMs);
               this.requestLayout();
            }
         }
      }
   }

   private void rebuildRows() { this.rebuildRowsNow(); }

   private boolean rowBuildActive;
   private long rowBuildGeneration;
   private int rowBuildCursor;
   @Nullable private Runnable rowBuildOnPrepared;
   private FrameLayout rowBuildContainer;
   private long rowBuildMeanCostNs=400_000L;
   private float[] rowBuildOldTranslation=new float[0];
   private List<String> rowBuildFallback=Collections.emptyList();
   private final android.view.Choreographer.FrameCallback rowBuildFrame=this::advanceRowPreload;

   private void rebuildRowsNow() {
      ++this.rowBuildGeneration;
      this.rowBuildActive=false;
      android.view.Choreographer.getInstance().removeFrameCallback(this.rowBuildFrame);
      this.finishBrowseHandoff();
      this.stopTrackAnimator();
      this.clearRowLagAnimations();
      this.clearCachedKaraokeHighlightState();
      this.cancelPressedFeedbackAnimator();
      this.manualPressedSourceIndex = -1;
      this.pressedFeedbackSourceIndex = -1;
      this.pressedFeedbackProgress = 0.0F;
      this.rowBuildOldTranslation = this.translationRowProgress;
      this.recycleRowHoldersToPool();
      this.rowHolders.clear();
      this.sourceGroups.clear();
      this.prepSourceGroups.clear();
      this.trackContainer.removeAllViews();
      this.trackContainer.addView(this.pressedRowCardView, new FrameLayout.LayoutParams(0, 0));
      this.pressedRowCardView.setVisibility(4);
      this.pressedRowCardView.setAlpha(0.0F);
      this.pressedRowCardView.setTranslationY(0.0F);
      this.lastRowsMeasureWidthPx = -1;

      this.rowBuildCursor=0;
      this.rowBuildContainer=this.isShown()?this.trackContainer:new FrameLayout(this.getContext());
      this.rowBuildFallback=!this.plainLines.isEmpty()?this.plainLines:Collections.singletonList(AppStrings.get(string.ui_lyrics_empty));
      this.rowBuildActive=true;
      if (!this.isShown()) {
         if(this.isAttachedToWindow())android.view.Choreographer.getInstance().postFrameCallback(this.rowBuildFrame);
         return;
      }
      while(this.rowBuildCursor<this.rowBuildCount())this.appendNextPreloadSource();
      this.finishRowsRebuild(this.rowBuildOldTranslation);
      this.rowBuildActive=false;
   }
   private int rowBuildCount(){return this.timedLines.isEmpty()?this.rowBuildFallback.size():this.timedLines.size();}
   private void appendNextPreloadSource(){
      int index=this.rowBuildCursor++;
      if(this.timedLines.isEmpty())this.appendPlainSource(index,this.rowBuildFallback);else this.appendTimedSource(index);
   }
   private void advanceRowPreload(long frameTimeNs){
      if(!this.rowBuildActive||!this.isAttachedToWindow())return;
      long generation=this.rowBuildGeneration;
      android.view.Display display=this.getDisplay();
      float hz=display==null?60f:display.getRefreshRate();
      long period=(long)(1_000_000_000d/Math.max(1f,hz));
      int remaining=this.rowBuildCount()-this.rowBuildCursor;
      int quota=com.lab.hv.util.NativeKernels.lyricPreloadQuota(period,System.nanoTime()-frameTimeNs,
            this.rowBuildMeanCostNs,remaining);
      long start=System.nanoTime();int built=0;
      // The loading screen waits for these rows, so a late frame must not starve preparation.
      // Keep the work sliced, but prepare at least one source row per callback.
      if(remaining>0)quota=Math.max(1,quota);
      while(built<quota&&this.rowBuildCursor<this.rowBuildCount()){
         this.appendNextPreloadSource();built++;
         if(System.nanoTime()-start>=Math.min(2_000_000L,period/8))break;
      }
      if(built>0){
         this.rowBuildMeanCostNs=Math.max(100_000L,(this.rowBuildMeanCostNs*3+(System.nanoTime()-start)/built)/4);
      }
      if(this.rowBuildCursor<this.rowBuildCount()){
         android.view.Choreographer.getInstance().postFrameCallback(this.rowBuildFrame);return;
      }
      this.finishRowsRebuild(this.rowBuildOldTranslation);this.rowBuildActive=false;
      this.activeSourceIndex=-1;this.visualSourceIndex=-1;
      this.resolveAndApplyActiveSource(true);this.scheduleTimedLyricsFrameLoopIfNeeded();
      // Listeners may synchronously replace lyrics and start another build. Take this
      // completion before dispatch, and never consume the next build's reveal callback.
      Runnable prepared=this.rowBuildOnPrepared;this.rowBuildOnPrepared=null;
      this.dispatchTranslationStateChangedIfNeeded();
      if(generation!=this.rowBuildGeneration)return;
      this.dispatchRowsReady();
      if(generation!=this.rowBuildGeneration)return;
      if(prepared!=null)this.prepareForRevealFrame(()->{
         if(generation==this.rowBuildGeneration&&!this.rowBuildActive)prepared.run();
      });
   }
   private void appendTimedSource(int sourceIndex){

            TimedLine line = (TimedLine)this.timedLines.get(sourceIndex);
            boolean prepCountdown = line.prepCountdown;
            List<String> rows = this.splitToFixedRows(line.text, 8, this.charTextPx);
            if (rows.isEmpty()) {
               rows = Collections.singletonList(" ");
            }

            int firstMainRowIndex = this.rowHolders.size();
            int mainRowCount = rows.size();
            int lineSearchUtf16 = 0;
            int fallbackLineCodePointCursor = 0;

            for(int rowIndex = 0; rowIndex < rows.size(); ++rowIndex) {
               String rowText = (String)rows.get(rowIndex);
               RowSourceMatch sourceMatch = this.resolveRowSourceMatch(line.text, rowText, lineSearchUtf16, fallbackLineCodePointCursor);
               CharRowFrame rowFrame = this.obtainRowFrame(false);
               rowFrame.setPrepDotsMode(prepCountdown);
               rowFrame.setRowText(rowText);
               this.rowBuildContainer.addView(rowFrame, new FrameLayout.LayoutParams(-1, this.rowHeightPx));
               this.rowHolders.add(new RowHolder(sourceIndex, 0, 0, false, prepCountdown, line.prepTargetTimeMs, sourceMatch.startCodePoint, sourceMatch.codePointCount, rowFrame));
               lineSearchUtf16 = sourceMatch.nextSearchUtf16;
               fallbackLineCodePointCursor = line.text.codePointCount(0, lineSearchUtf16);
            }

            int firstTranslationRowIndex = -1;
            int translationRowCount = 0;
            String translation = prepCountdown ? null : (sourceIndex < this.timedTranslations.size() ? (String)this.timedTranslations.get(sourceIndex) : null);
            if (!TextUtils.isEmpty(translation)) {
               List<String> trRows = this.splitToFixedRows(translation, 16, this.translationCharTextPx);
               if (trRows.isEmpty()) {
                  trRows = Collections.singletonList(" ");
               }

               firstTranslationRowIndex = this.rowHolders.size();
               translationRowCount = trRows.size();
               int translationSearchUtf16 = 0;
               int fallbackTranslationCodePointCursor = 0;

               for(int rowIndex = 0; rowIndex < trRows.size(); ++rowIndex) {
                  String trRowText = (String)trRows.get(rowIndex);
                  RowSourceMatch sourceMatch = this.resolveRowSourceMatch(translation, trRowText, translationSearchUtf16, fallbackTranslationCodePointCursor);
                  CharRowFrame trRow = this.obtainRowFrame(true);
                  trRow.setPrepDotsMode(false);
                  trRow.setRowText(trRowText);
                  this.rowBuildContainer.addView(trRow, new FrameLayout.LayoutParams(-1, this.translationRowHeightPx));
                  this.rowHolders.add(new RowHolder(sourceIndex, 0, 0, true, false, -1L, sourceMatch.startCodePoint, sourceMatch.codePointCount, trRow));
                  translationSearchUtf16 = sourceMatch.nextSearchUtf16;
                  fallbackTranslationCodePointCursor = translation.codePointCount(0, translationSearchUtf16);
               }
            }

            int lastRowIndex = this.rowHolders.size() - 1;
            SourceGroup sourceGroup = new SourceGroup(line.timeMs, sourceIndex, firstMainRowIndex, mainRowCount, firstTranslationRowIndex, translationRowCount, firstMainRowIndex, lastRowIndex, 0.0F, prepCountdown, line.prepTargetTimeMs);
            this.sourceGroups.add(sourceGroup);
            if (prepCountdown) {
               this.prepSourceGroups.add(sourceGroup);
            }
         
   }
   private void appendPlainSource(int sourceIndex,List<String> fallbackLines){

            List<String> rows = this.splitToFixedRows((String)fallbackLines.get(sourceIndex), 8, this.charTextPx);
            if (rows.isEmpty()) {
               rows = Collections.singletonList(" ");
            }

            int firstMainRowIndex = this.rowHolders.size();
            int mainRowCount = rows.size();
            int lineSearchUtf16 = 0;
            int fallbackLineCodePointCursor = 0;
            String fallbackLine = (String)fallbackLines.get(sourceIndex);

            for(int rowIndex = 0; rowIndex < rows.size(); ++rowIndex) {
               String rowText = (String)rows.get(rowIndex);
               RowSourceMatch sourceMatch = this.resolveRowSourceMatch(fallbackLine, rowText, lineSearchUtf16, fallbackLineCodePointCursor);
               CharRowFrame rowFrame = this.obtainRowFrame(false);
               rowFrame.setPrepDotsMode(false);
               rowFrame.setRowText(rowText);
               this.rowBuildContainer.addView(rowFrame, new FrameLayout.LayoutParams(-1, this.rowHeightPx));
               this.rowHolders.add(new RowHolder(sourceIndex, 0, 0, false, false, -1L, sourceMatch.startCodePoint, sourceMatch.codePointCount, rowFrame));
               lineSearchUtf16 = sourceMatch.nextSearchUtf16;
               fallbackLineCodePointCursor = fallbackLine.codePointCount(0, lineSearchUtf16);
            }

            int firstTranslationRowIndex = -1;
            int translationRowCount = 0;
            String translation = sourceIndex < this.plainTranslations.size() ? (String)this.plainTranslations.get(sourceIndex) : null;
            if (!TextUtils.isEmpty(translation)) {
               List<String> trRows = this.splitToFixedRows(translation, 16, this.translationCharTextPx);
               if (trRows.isEmpty()) {
                  trRows = Collections.singletonList(" ");
               }

               firstTranslationRowIndex = this.rowHolders.size();
               translationRowCount = trRows.size();
               int translationSearchUtf16 = 0;
               int fallbackTranslationCodePointCursor = 0;

               for(int rowIndex = 0; rowIndex < trRows.size(); ++rowIndex) {
                  String trRowText = (String)trRows.get(rowIndex);
                  RowSourceMatch sourceMatch = this.resolveRowSourceMatch(translation, trRowText, translationSearchUtf16, fallbackTranslationCodePointCursor);
                  CharRowFrame trRow = this.obtainRowFrame(true);
                  trRow.setPrepDotsMode(false);
                  trRow.setRowText(trRowText);
                  this.rowBuildContainer.addView(trRow, new FrameLayout.LayoutParams(-1, this.translationRowHeightPx));
                  this.rowHolders.add(new RowHolder(sourceIndex, 0, 0, true, false, -1L, sourceMatch.startCodePoint, sourceMatch.codePointCount, trRow));
                  translationSearchUtf16 = sourceMatch.nextSearchUtf16;
                  fallbackTranslationCodePointCursor = translation.codePointCount(0, translationSearchUtf16);
               }
            }

            int lastRowIndex = this.rowHolders.size() - 1;
            this.sourceGroups.add(new SourceGroup(-1L, sourceIndex, firstMainRowIndex, mainRowCount, firstTranslationRowIndex, translationRowCount, firstMainRowIndex, lastRowIndex, 0.0F, false, -1L));
         
   }
   private void finishRowsRebuild(float[] oldTranslationProgress){
      if(this.rowBuildContainer!=this.trackContainer){
         // Attach already prepared rows once; hidden preloading never invalidates the whole
         // window for each new glyph View. No text, translation or karaoke timing is omitted.
         while(this.rowBuildContainer.getChildCount()>0){
            View row=this.rowBuildContainer.getChildAt(0);
            this.rowBuildContainer.removeViewAt(0);this.trackContainer.addView(row);
         }
      }
      int translationCount = 0;

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         if (((RowHolder)this.rowHolders.get(i)).translation) {
            ++translationCount;
         }
      }

      this.translationIndexByHolder = new int[this.rowHolders.size()];

      for(int i = 0; i < this.translationIndexByHolder.length; ++i) {
         this.translationIndexByHolder[i] = -1;
      }

      this.translationRowProgress = new float[translationCount];
      this.translationGeometryVersion++;
      this.translationSourceByIndex = new int[translationCount];
      int cursor = 0;

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         if (((RowHolder)this.rowHolders.get(i)).translation) {
            this.translationIndexByHolder[i] = cursor;
            this.translationSourceByIndex[cursor] = ((RowHolder)this.rowHolders.get(i)).sourceIndex;
            if (cursor < oldTranslationProgress.length) {
               this.translationRowProgress[cursor] = this.clampAlpha(oldTranslationProgress[cursor]);
            } else {
               this.translationRowProgress[cursor] = this.translationVisible ? 1.0F : 0.0F;
            }

            ++cursor;
         }
      }

      if (translationCount == 0) {
         this.translationVisibilityProgress = this.translationVisible ? 1.0F : 0.0F;
      }

      this.refreshKaraokeGlyphLiftConfiguration();
      this.appliedFocusGeometrySignature = Long.MIN_VALUE;
      this.updateDynamicRowGeometry();
      long renderPositionMs = this.resolveRenderPositionMs();
      this.refreshRowColors();
      this.refreshRowAlphaState(renderPositionMs);
      this.refreshPrepCountdownRows(renderPositionMs);
      this.requestLayout();
   }

   private void updateDynamicRowGeometry() {
      boolean metricsChanged = this.geometryRowHeight != this.rowHeightPx
            || this.geometryWrapGap != this.wrapRowGapPx
            || this.geometryTranslationHeight != this.translationRowHeightPx
            || this.geometryTranslationTopGap != this.translationTopGapPx
            || this.geometryTranslationWrapGap != this.translationWrapRowGapPx
            || this.geometrySentenceGap != this.sentenceGapPx;
      this.geometryRowHeight = this.rowHeightPx;
      this.geometryWrapGap = this.wrapRowGapPx;
      this.geometryTranslationHeight = this.translationRowHeightPx;
      this.geometryTranslationTopGap = this.translationTopGapPx;
      this.geometryTranslationWrapGap = this.translationWrapRowGapPx;
      this.geometrySentenceGap = this.sentenceGapPx;
      int topCursor = this.trackEdgeSpacerPx;
      int lastSentenceGapPx = 0;
      long renderPositionMs = this.resolveRenderPositionMs();

      for(int i = 0; i < this.sourceGroups.size(); ++i) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(i);
         float focusProgress = this.resolveSourceControlFocusProgress(group.sourceIndex, group.prepCountdown);
         float controlScale = 1.0F + 0.04F * focusProgress;
         float prepRevealProgress = group.prepCountdown ? this.resolveStablePrepRevealProgress(group, renderPositionMs) : 1.0F;
         // A focus change changes only the outgoing/incoming sentence's local geometry.
         // Later sentences keep their exact integer row frames, shifted by the prefix delta.
         // Translation cascades and countdowns retain their original per-row rounding below.
         if (!metricsChanged && group.geometryControlScale == controlScale
               && group.geometryPrepReveal == prepRevealProgress
               && (group.translationRowCount == 0
                     || group.geometryTranslationVersion == this.translationGeometryVersion)) {
            int delta = topCursor - group.geometryTopPx;
            if (delta != 0) {
               for (int r = group.firstRowIndex; r <= group.lastRowIndex; r++) {
                  RowHolder holder = (RowHolder)this.rowHolders.get(r);
                  holder.top += delta;
                  holder.bottom += delta;
               }
            }
            group.geometryTopPx = topCursor;
            group.topYInTrack = (float)topCursor;
            topCursor += group.geometryHeightPx;
            lastSentenceGapPx = group.geometrySentenceGapPx;
            continue;
         }
         group.geometryTopPx = topCursor;
         int scaledMainRowHeightPx = Math.max(1, Math.round((float)this.rowHeightPx * controlScale));
         int scaledWrapRowGapPx = Math.max(0, Math.round((float)this.wrapRowGapPx * controlScale));
         float scaledTranslationTopGapPx = (float)this.translationTopGapPx * controlScale;
         float scaledTranslationRowHeightPx = (float)this.translationRowHeightPx * controlScale;
         float scaledTranslationWrapGapPx = (float)this.translationWrapRowGapPx * controlScale;
         group.topYInTrack = (float)topCursor;

         for(int rowIndex = 0; rowIndex < group.mainRowCount; ++rowIndex) {
            RowHolder holder = (RowHolder)this.rowHolders.get(group.firstMainRowIndex + rowIndex);
            int rowHeight = Math.max(0, Math.round((float)scaledMainRowHeightPx * prepRevealProgress));
            holder.top = topCursor;
            holder.bottom = topCursor + rowHeight;
            topCursor = holder.bottom;
            if (rowIndex < group.mainRowCount - 1) {
               topCursor += Math.round((float)scaledWrapRowGapPx * prepRevealProgress);
            }
         }

         if (group.translationRowCount > 0 && group.firstTranslationRowIndex >= 0) {
            float firstProgress = this.resolveTranslationRowProgressByHolderIndex(group.firstTranslationRowIndex);
            topCursor += Math.round(scaledTranslationTopGapPx * firstProgress);

            for(int rowIndex = 0; rowIndex < group.translationRowCount; ++rowIndex) {
               int holderIndex = group.firstTranslationRowIndex + rowIndex;
               RowHolder holder = (RowHolder)this.rowHolders.get(holderIndex);
               float rowProgress = this.resolveTranslationRowProgressByHolderIndex(holderIndex);
               int rowHeight = Math.max(0, Math.round(scaledTranslationRowHeightPx * rowProgress));
               holder.top = topCursor;
               holder.bottom = topCursor + rowHeight;
               topCursor = holder.bottom;
               if (rowIndex < group.translationRowCount - 1) {
                  int nextHolderIndex = holderIndex + 1;
                  float nextProgress = this.resolveTranslationRowProgressByHolderIndex(nextHolderIndex);
                  float gapProgress = Math.min(rowProgress, nextProgress);
                  topCursor += Math.round(scaledTranslationWrapGapPx * gapProgress);
               }
            }
         }

         lastSentenceGapPx = Math.round((float)this.sentenceGapPx * prepRevealProgress);
         topCursor += lastSentenceGapPx;
         group.geometryHeightPx = topCursor - group.geometryTopPx;
         group.geometrySentenceGapPx = lastSentenceGapPx;
         group.geometryControlScale = controlScale;
         group.geometryPrepReveal = prepRevealProgress;
         group.geometryTranslationVersion = this.translationGeometryVersion;
      }

      if (!this.sourceGroups.isEmpty()) {
         topCursor -= lastSentenceGapPx;
      }

      topCursor += this.trackEdgeSpacerPx;
      this.contentHeightPx = Math.max(1, topCursor);
      if (this.translationRowProgress.length == 0) {
         this.translationVisibilityProgress = this.translationVisible ? 1.0F : 0.0F;
      } else {
         float sum = 0.0F;

         for(int i = 0; i < this.translationRowProgress.length; ++i) {
            sum += this.clampAlpha(this.translationRowProgress[i]);
         }

         this.translationVisibilityProgress = this.clampAlpha(sum / (float)this.translationRowProgress.length);
      }
   }

   private float resolveSourceControlFocusProgress(int sourceIndex, boolean prepCountdown) {
      if (!prepCountdown && !this.manualScrollActive && sourceIndex >= 0) {
         if (this.sourceAlphaTransitionActive) {
            if (sourceIndex == this.sourceAlphaTransitionFrom) {
               return 1.0F - this.clampAlpha(this.sourceAlphaTransitionProgress);
            }

            if (sourceIndex == this.sourceAlphaTransitionTo) {
               return this.clampAlpha(this.sourceAlphaTransitionProgress);
            }
         }

         return sourceIndex == this.resolveSettledSourceIndex() ? 1.0F : 0.0F;
      } else {
         return 0.0F;
      }
   }

   private void applyFocusDrivenGeometry(boolean preserveTrackTranslation, int anchorSourceIndex, boolean force) {
      long signature = this.buildFocusGeometrySignature();
      if (force || signature != this.appliedFocusGeometrySignature) {
         float previousTrackTranslationY = this.trackContainer.getTranslationY();
         float anchorTopBefore = anchorSourceIndex >= 0 ? this.resolveSourceTopYInTrack(anchorSourceIndex) : Float.NaN;
         this.updateDynamicRowGeometry();
         this.measureTrackContainerToCurrentGeometry();
         this.layoutDynamicRowsIfPossible();
         this.refreshRowContentPresentationScale();
         this.layoutNonLyricHintIfPossible();
         if (preserveTrackTranslation && anchorSourceIndex >= 0 && !Float.isNaN(anchorTopBefore)) {
            float anchorTopAfter = this.resolveSourceTopYInTrack(anchorSourceIndex);
            if (!Float.isNaN(anchorTopAfter)) {
               this.trackContainer.setTranslationY(this.clampTrackTranslationY(previousTrackTranslationY + (anchorTopBefore - anchorTopAfter)));
            }
         }

         this.refreshPressedFeedbackVisuals();
         this.appliedFocusGeometrySignature = signature;
      }
   }

   private void refreshRowContentPresentationScale() {
      for (int i = 0; i < this.sourceGroups.size(); i++) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(i);
         float focusProgress = this.resolveSourceControlFocusProgress(group.sourceIndex, group.prepCountdown);
         float presentationScale = 1.0F + 0.04F * focusProgress;
         float passedProgress = this.resolvePassedSourceVisualProgress(group.sourceIndex);
         if (passedProgress > 0.0F) {
            presentationScale *= 1.0F + (PASSED_SOURCE_MIN_SCALE - 1.0F) * passedProgress;
         }
         if (group.presentationScale == presentationScale) continue;
         group.presentationScale = presentationScale;
         for (int r = group.firstRowIndex; r <= group.lastRowIndex; r++) {
            ((RowHolder)this.rowHolders.get(r)).row.setContentPresentationScale(presentationScale);
         }
      }
   }

   private long buildFocusGeometrySignature() {
      long signature = 17L;
      signature = signature * 31L + (this.manualScrollActive ? 1L : 0L);
      signature = signature * 31L + (this.sourceAlphaTransitionActive ? 1L : 0L);
      signature = signature * 31L + (long)this.visualSourceIndex + 3L;
      signature = signature * 31L + (this.progressScrubbingActive ? 1L : 0L);
      signature = signature * 31L + (long)Math.round(this.passedRevealProgress * 1000.0F);
      signature = signature * 31L + (long)this.sourceAlphaTransitionFrom + 3L;
      signature = signature * 31L + (long)this.sourceAlphaTransitionTo + 3L;
      signature = signature * 31L + (long)Math.round(this.clampAlpha(this.sourceAlphaTransitionProgress) * 1000.0F);
      signature = signature * 31L + (long)this.resolveSettledSourceIndex() + 3L;
      signature = signature * 31L + this.buildPrepRevealGeometrySignature(this.resolveRenderPositionMs());
      return signature;
   }

   private long buildPrepRevealGeometrySignature(long renderPositionMs) {
      long signature = 0L;

      for(int i = 0; i < this.prepSourceGroups.size(); ++i) {
         SourceGroup group = (SourceGroup)this.prepSourceGroups.get(i);
         signature = signature * 31L + (long)Math.round(this.resolveStablePrepRevealProgress(group, renderPositionMs) * 1000.0F);
      }

      return signature;
   }

   private int makeRowWidthMeasureSpec(int logicalWidthPx) {
      int overflowPx = this.rowBlurBoundsOverflowPx * 2;
      return MeasureSpec.makeMeasureSpec(Math.max(1, logicalWidthPx + overflowPx), 1073741824);
   }

   private int makeRowHeightMeasureSpec(int logicalHeightPx) {
      int overflowPx = this.rowBlurBoundsOverflowPx * 2;
      return MeasureSpec.makeMeasureSpec(Math.max(1, logicalHeightPx + overflowPx), 1073741824);
   }

   private void measureTrackContainerToCurrentGeometry() {
      int innerWidth = Math.max(0, this.getWidth() - this.getPaddingLeft() - this.getPaddingRight());
      int innerHeight = Math.max(0, this.getHeight() - this.getPaddingTop() - this.getPaddingBottom());
      if (innerWidth > 0 && innerHeight > 0) {
         this.trackContainer.measure(MeasureSpec.makeMeasureSpec(innerWidth, 1073741824), MeasureSpec.makeMeasureSpec(Math.max(1, this.contentHeightPx), 1073741824));
      }
   }

   private void layoutDynamicRowsIfPossible() {
      int left = this.getPaddingLeft();
      int top = this.getPaddingTop();
      int right = this.getWidth() - this.getPaddingRight();
      int bottom = this.getHeight() - this.getPaddingBottom();
      int width = Math.max(0, right - left);
      int height = Math.max(0, bottom - top);
      if (width > 0 && height > 0) {
         this.trackContainer.layout(left, top, left + width, top + Math.max(1, this.contentHeightPx));
         int overflowPx = this.rowBlurBoundsOverflowPx;

         for(int i = 0; i < this.rowHolders.size(); ++i) {
            RowHolder holder = (RowHolder)this.rowHolders.get(i);
            int rowLeft = -overflowPx;
            int rowTop = holder.top - overflowPx;
            int rowRight = width + overflowPx;
            int rowBottom = holder.bottom + overflowPx;
            boolean layoutRequested = holder.row.isLayoutRequested();
            if (layoutRequested) {
               // RowTrackLayout does not measure rows; a row whose glyphs asked for layout is
               // measured here at the frame it is about to get.
               holder.row.measure(MeasureSpec.makeMeasureSpec(rowRight - rowLeft, 1073741824), MeasureSpec.makeMeasureSpec(Math.max(0, rowBottom - rowTop), 1073741824));
            }

            if (layoutRequested || holder.row.isLayoutRequested()
                  || holder.row.getLeft() != rowLeft || holder.row.getTop() != rowTop
                  || holder.row.getRight() != rowRight || holder.row.getBottom() != rowBottom) {
               holder.row.layout(rowLeft, rowTop, rowRight, rowBottom);
            }
         }

         if (this.presentationProgress < 0.9999F) {
            this.applyPresentationToRows();
         }

         this.refreshPressedFeedbackVisuals();
      }
   }

   private void layoutNonLyricHintIfPossible() {
      int left = this.getPaddingLeft();
      int top = this.getPaddingTop();
      int right = this.getWidth() - this.getPaddingRight();
      int bottom = this.getHeight() - this.getPaddingBottom();
      int width = Math.max(0, right - left);
      int height = Math.max(0, bottom - top);
      if (width > 0 && height > 0) {
         int hintW = Math.min(width, this.nonLyricHintView.getMeasuredWidth());
         int hintH = this.nonLyricHintView.getMeasuredHeight();
         int hintTop = top + Math.max(0, Math.round(this.edgeFadeTopPx + this.nonLyricHintTopOffsetPx));
         int maxHintTop = Math.max(top, bottom - hintH);
         if (hintTop > maxHintTop) {
            hintTop = maxHintTop;
         }

         this.nonLyricHintView.layout(left, hintTop, left + hintW, hintTop + hintH);

      }
   }

   private void animateTranslationToggle(float targetProgress) {
      this.cancelTranslationToggleAnimator();
      final float endProgress = this.clampAlpha(targetProgress);
      if (this.translationRowProgress.length == 0) {
         this.translationVisibilityProgress = endProgress;
         this.updateDynamicRowGeometry();
         this.refreshRowAlphaState(this.resolveRenderPositionMs());
         this.refreshPrepCountdownRows(this.resolveRenderPositionMs());
         this.layoutDynamicRowsIfPossible();
         this.layoutNonLyricHintIfPossible();
         if (this.trackAnimator == null || !this.trackAnimator.isRunning()) {
            this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
         }

         this.invalidate();
      } else {
         float[] startRowProgress = new float[this.translationRowProgress.length];
         System.arraycopy(this.translationRowProgress, 0, startRowProgress, 0, this.translationRowProgress.length);
         boolean allSettled = true;

         for(int i = 0; i < startRowProgress.length; ++i) {
            if (Math.abs(startRowProgress[i] - endProgress) > 0.001F) {
               allSettled = false;
               break;
            }
         }

         if (!allSettled) {
            final long token = (long)(++this.translationToggleToken);
            int rowCount = this.translationRowProgress.length;
            int anchorRowIndex = this.resolveTranslationToggleAnchorRowIndex();
            long[] rowDelayMs = new long[rowCount];
            long maxRowDelayMs = 0L;

            for(int i = 0; i < rowCount; ++i) {
               int distance = anchorRowIndex < 0 ? i : Math.abs(i - anchorRowIndex);
               long delay = (long)distance * 0L;
               rowDelayMs[i] = delay;
               if (delay > maxRowDelayMs) {
                  maxRowDelayMs = delay;
               }
            }

            long totalDurationMs = 500L + maxRowDelayMs;
            final ValueAnimator animator = com.lab.hv.RustValueAnimator.ofFloat(new float[]{0.0F, 1.0F});
            this.translationToggleAnimator = animator;
            animator.setDuration(totalDurationMs);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener((a) -> {
               if (token == (long)this.translationToggleToken && this.translationToggleAnimator == animator) {
                  float master = (Float)a.getAnimatedValue();
                  long elapsedMs = (long)Math.round(master * (float)totalDurationMs);
                  this.translationGeometryVersion++;

                  for(int i = 0; i < this.translationRowProgress.length; ++i) {
                     float local = this.clampAlpha((float)(elapsedMs - rowDelayMs[i]) / (float)Math.max(1L, 500L));
                     float eased = this.translationToggleInterpolator.getInterpolation(local);
                     this.translationRowProgress[i] = startRowProgress[i] + (endProgress - startRowProgress[i]) * eased;
                  }

                  this.updateDynamicRowGeometry();
                  long renderPositionMs = this.resolveRenderPositionMs();
                  this.refreshRowAlphaState(renderPositionMs);
                  this.refreshPrepCountdownRows(renderPositionMs);
                  this.layoutDynamicRowsIfPossible();
                  this.layoutNonLyricHintIfPossible();
                  if (this.trackAnimator == null || !this.trackAnimator.isRunning()) {
                     this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
                  }

                  this.postInvalidateOnAnimation();
               }
            });
            animator.addListener(new AnimatorListenerAdapter() {
               public void onAnimationEnd(Animator animation) {
                  if (token == (long)NowPlayingLyricsTickerView.this.translationToggleToken && NowPlayingLyricsTickerView.this.translationToggleAnimator == animator) {
                     NowPlayingLyricsTickerView.this.translationToggleAnimator = null;
                     NowPlayingLyricsTickerView.this.setAllTranslationRowProgress(endProgress);
                     NowPlayingLyricsTickerView.this.translationVisibilityProgress = endProgress;
                     NowPlayingLyricsTickerView.this.updateDynamicRowGeometry();
                     long renderPositionMs = NowPlayingLyricsTickerView.this.resolveRenderPositionMs();
                     NowPlayingLyricsTickerView.this.refreshRowAlphaState(renderPositionMs);
                     NowPlayingLyricsTickerView.this.refreshPrepCountdownRows(renderPositionMs);
                     NowPlayingLyricsTickerView.this.layoutDynamicRowsIfPossible();
                     NowPlayingLyricsTickerView.this.layoutNonLyricHintIfPossible();
                     if (NowPlayingLyricsTickerView.this.trackAnimator == null || !NowPlayingLyricsTickerView.this.trackAnimator.isRunning()) {
                        NowPlayingLyricsTickerView.this.trackContainer.setTranslationY(NowPlayingLyricsTickerView.this.resolveTrackTranslationY(NowPlayingLyricsTickerView.this.resolveSettledSourceIndex()));
                     }

                     NowPlayingLyricsTickerView.this.invalidate();
                  }
               }

               public void onAnimationCancel(Animator animation) {
                  if (token == (long)NowPlayingLyricsTickerView.this.translationToggleToken && NowPlayingLyricsTickerView.this.translationToggleAnimator == animator) {
                     NowPlayingLyricsTickerView.this.translationToggleAnimator = null;
                     NowPlayingLyricsTickerView.this.updateDynamicRowGeometry();
                     long renderPositionMs = NowPlayingLyricsTickerView.this.resolveRenderPositionMs();
                     NowPlayingLyricsTickerView.this.refreshRowAlphaState(renderPositionMs);
                     NowPlayingLyricsTickerView.this.refreshPrepCountdownRows(renderPositionMs);
                     NowPlayingLyricsTickerView.this.layoutDynamicRowsIfPossible();
                     NowPlayingLyricsTickerView.this.layoutNonLyricHintIfPossible();
                     if (NowPlayingLyricsTickerView.this.trackAnimator == null || !NowPlayingLyricsTickerView.this.trackAnimator.isRunning()) {
                        NowPlayingLyricsTickerView.this.trackContainer.setTranslationY(NowPlayingLyricsTickerView.this.resolveTrackTranslationY(NowPlayingLyricsTickerView.this.resolveSettledSourceIndex()));
                     }

                  }
               }
            });
            animator.start();
         } else {
            this.translationVisibilityProgress = endProgress;
            this.updateDynamicRowGeometry();
            long renderPositionMs = this.resolveRenderPositionMs();
            this.refreshRowAlphaState(renderPositionMs);
            this.refreshPrepCountdownRows(renderPositionMs);
            this.layoutDynamicRowsIfPossible();
            this.layoutNonLyricHintIfPossible();
            if (this.trackAnimator == null || !this.trackAnimator.isRunning()) {
               this.trackContainer.setTranslationY(this.resolveTrackTranslationY(this.resolveSettledSourceIndex()));
            }

            this.invalidate();
         }
      }
   }

   private int resolveTranslationToggleAnchorRowIndex() {
      if (this.translationRowProgress.length == 0) {
         return -1;
      } else {
         int anchorSourceIndex = this.resolveSettledSourceIndex();
         if (anchorSourceIndex < 0 || anchorSourceIndex >= this.sourceGroups.size()) {
            int resolved = this.resolveCurrentSourceIndex(this.resolveRenderPositionMs());
            if (resolved >= 0) {
               anchorSourceIndex = resolved;
            }
         }

         if (anchorSourceIndex < 0) {
            return 0;
         } else {
            int bestIndex = -1;
            int bestDistance = Integer.MAX_VALUE;

            for(int i = 0; i < this.translationSourceByIndex.length; ++i) {
               int distance = Math.abs(this.translationSourceByIndex[i] - anchorSourceIndex);
               if (distance < bestDistance) {
                  bestDistance = distance;
                  bestIndex = i;
                  if (distance == 0) {
                     break;
                  }
               }
            }

            return bestIndex >= 0 ? bestIndex : 0;
         }
      }
   }

   private void cancelTranslationToggleAnimator() {
      if (this.translationToggleAnimator != null) {
         try {
            this.translationToggleAnimator.cancel();
         } catch (Throwable var2) {
         }

         this.translationToggleAnimator = null;
         ++this.translationToggleToken;
      }
   }

   private void recycleRowHoldersToPool() {
      // A cancelled hidden build owns rows in a detached staging container, not necessarily
      // trackContainer. Detach BOTH owners before pooling; resetting the visible container
      // later cannot release staging children and would make the next addView() crash.
      // Bulk removal avoids per-row parent scans and avoids invalidating attached rows while
      // resetting their presentation state. All main/translation rows are still reused.
      this.trackContainer.removeAllViews();
      if(this.rowBuildContainer!=null&&this.rowBuildContainer!=this.trackContainer){
         this.rowBuildContainer.removeAllViews();
      }
      this.rowBuildContainer=null;
      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         holder.row.setPresentationTransform(0.0F, 1.0F);
         holder.row.setTranslationY(0.0F);
         holder.row.setKaraokeGlyphPresentationPx(0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
         holder.row.setAlpha(1.0F);
         holder.row.setVisibility(VISIBLE);
         holder.row.setPressedFeedbackProgress(0.0F);
         if (holder.translation) {
            this.translationRowPool.add(holder.row);
         } else {
            this.mainRowPool.add(holder.row);
         }
      }

   }

   @NonNull
   private CharRowFrame obtainRowFrame(boolean translation) {
      ArrayList<CharRowFrame> pool = translation ? this.translationRowPool : this.mainRowPool;
      if (!pool.isEmpty()) {
         CharRowFrame rowFrame = (CharRowFrame)pool.remove(pool.size() - 1);
         rowFrame.setBlurBoundsOverflowPx(this.rowBlurBoundsOverflowPx);
         rowFrame.setPressedContentSinkPx(this.touchPressRowSinkPx);
         return rowFrame;
      } else {
         float textSizePx = translation ? this.translationCharTextPx : this.charTextPx;
         float weightPx = translation ? this.translationWeightStrengthPx : this.mainWeightStrengthPx;
         CharRowFrame rowFrame = new CharRowFrame(this.getContext(), textSizePx, weightPx, this.lyricsTypeface, this.glyphEdgePaddingPx, this.introPrepDotRestBlurPx, this.blurRadiusQuantizePx);
         rowFrame.setBlurBoundsOverflowPx(this.rowBlurBoundsOverflowPx);
         rowFrame.setPressedContentSinkPx(this.touchPressRowSinkPx);
         return rowFrame;
      }
   }

   private void refreshRowColors() {
      this.clearCachedKaraokeHighlightState();
      int activeMainColor = this.withColorAlpha(this.activeTextColor, 1.0F);
      int activeTranslationColor = this.withColorAlpha(this.activeTextColor, TRANSLATION_ALPHA);
      int inactiveMainColor = this.withColorAlpha(this.activeTextColor, 1.0F);
      int inactiveTranslationColor = this.withColorAlpha(this.activeTextColor, TRANSLATION_ALPHA);
      boolean useHdrHighlight = this.shouldUseHdrHighlightColors();
      boolean allowBlur = !this.disableBlurForCurrentContent && this.lyricsDepthBlurEnabled && !this.forceDisableRowBlur && !this.manualScrollNoBlur && !this.progressScrubbingActive;
      long renderPositionMs = this.resolveRenderPositionMs();
      CollapsedKaraokeSpan collapsedSpan = this.resolveCollapsedKaraokeSpan(renderPositionMs);

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         boolean active = holder.sourceIndex == this.visualSourceIndex;
         float hdrProgress = useHdrHighlight ? this.resolveSourceHdrHighlightProgress(holder.sourceIndex) : 0.0F;
         float blurRadiusPx = this.resolveRowBlurRadiusPx(holder, allowBlur, collapsedSpan);
         if (holder.translation) {
            long activeTranslationHdrColor = useHdrHighlight ? this.hdrHighlightTextColor(TRANSLATION_ALPHA, hdrProgress, this.hdrWhiteRatio) : 0L;
            holder.row.setRowVisualState(active, activeTranslationColor, inactiveTranslationColor, activeTranslationHdrColor, useHdrHighlight && hdrProgress > 0.001F, blurRadiusPx);
         } else {
            long activeMainHdrColor = useHdrHighlight ? this.hdrHighlightTextColor(1.0F, hdrProgress, this.hdrWhiteRatio) : 0L;
            holder.row.setRowVisualState(active, activeMainColor, inactiveMainColor, activeMainHdrColor, useHdrHighlight && hdrProgress > 0.001F, blurRadiusPx);
         }
      }

      this.refreshActiveKaraokeHighlight(renderPositionMs);
   }

   private void refreshRowAlphaState(long renderPositionMs) {
      // While browsing the highlight still moves; any geometry change keeps the line in the
      // middle of the view where it is instead of moving the lines under the finger.
      if (this.manualScrollActive) {
         this.applyFocusDrivenGeometry(true, this.resolveBrowseAnchorSourceIndex(), false);
      } else {
         this.applyFocusDrivenGeometry(false, -1, false);
      }
      CollapsedKaraokeSpan collapsedSpan = this.resolveCollapsedKaraokeSpan(renderPositionMs);
      if (this.shouldRefreshRowBlurStateOnFrame(collapsedSpan)) {
         this.refreshRowBlurState(collapsedSpan);
      }

      this.refreshActiveKaraokeHighlight(renderPositionMs);

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         // A row away from the viewport is hidden whatever its alpha: its state waits until it
         // comes back (applyRowRenderState).
         if (this.isRowNearRenderViewport(holder)) {
            this.refreshRowBaseState(holder, i, renderPositionMs, collapsedSpan);
         }

         this.applyRowRenderState(holder);
      }

   }

   private void refreshRowBaseState(@NonNull RowHolder holder, int holderIndex, long renderPositionMs, @Nullable CollapsedKaraokeSpan collapsedSpan) {
      float sourceAlpha = this.resolveSourceAlpha(holder.sourceIndex, renderPositionMs, collapsedSpan);
      if (holder.prepCountdown && !holder.translation) {
         sourceAlpha *= this.resolvePrepRowVisibilityAlpha(holder.sourceIndex, holder.prepTargetTimeMs, renderPositionMs);
      }

      if (holder.translation) {
         sourceAlpha *= this.resolveTranslationRowProgressByHolderIndex(holderIndex);
      }

      float passedProgress = this.resolvePassedSourceVisualProgress(holder.sourceIndex);
      if (passedProgress > 0.0F) {
         sourceAlpha *= 1.0F - passedProgress;
      }

      holder.baseAlphaWithoutEdgeFade = this.clampAlpha(sourceAlpha);
      this.refreshRowHdrHighlightState(holder, this.shouldUseHdrHighlightColors());
      holder.baseStateStale = false;
   }

   private void refreshRowEdgeFadeState() {
      for(int i = 0; i < this.rowHolders.size(); ++i) {
         this.refreshRowEdgeFadeState((RowHolder)this.rowHolders.get(i));
      }

   }

   private void refreshRowEdgeFadeState(@NonNull RowHolder holder) {
      this.applyRowRenderState(holder);
   }

   private void applyRowRenderState(@NonNull RowHolder holder) {
      if (!this.isRowNearRenderViewport(holder)) {
         holder.baseStateStale = true;
         if (holder.row.getVisibility() != INVISIBLE) {
            holder.row.setVisibility(INVISIBLE);
         }

         return;
      }

      if (holder.baseStateStale) {
         // Back near the viewport on a move (a lag step, a scroll) before the next full refresh.
         long renderPositionMs = this.resolveRenderPositionMs();
         this.refreshRowBaseState(holder, this.rowHolders.indexOf(holder), renderPositionMs, this.resolveCollapsedKaraokeSpan(renderPositionMs));
      }

      float rowAlpha = holder.baseAlphaWithoutEdgeFade * this.resolveRowEdgeFadeAlpha(holder);
      if (!(rowAlpha > ROW_RENDER_ALPHA_EPSILON)) {
         if (holder.row.getVisibility() != INVISIBLE) {
            holder.row.setVisibility(INVISIBLE);
         }

         return;
      }

      if (holder.row.getVisibility() != VISIBLE) {
         holder.row.setVisibility(VISIBLE);
      }

      if (Math.abs(holder.row.getAlpha() - rowAlpha) > ROW_RENDER_ALPHA_EPSILON) {
         holder.row.setAlpha(rowAlpha);
      }
   }

   private void refreshRowHdrHighlightState(@NonNull RowHolder holder, boolean useHdrHighlight) {
      if (!useHdrHighlight) {
         holder.row.setRowHdrVisualState(false, 0L);
         return;
      }

      float hdrProgress = this.resolveSourceHdrHighlightProgress(holder.sourceIndex);
      if (hdrProgress <= 0.001F) {
         holder.row.setRowHdrVisualState(false, 0L);
         return;
      }

      float alphaRatio = holder.translation ? 0.7F : 1.0F;
      holder.row.setRowHdrVisualState(true, this.hdrHighlightTextColor(alphaRatio, hdrProgress, this.hdrWhiteRatio));
   }

   private float resolveRowEdgeFadeAlpha(@NonNull RowHolder holder) {
      int viewportTop = this.getPaddingTop();
      int viewportBottom = this.getHeight() - this.getPaddingBottom();
      if (viewportBottom <= viewportTop) {
         return 1.0F;
      } else {
         float trackY = (float)this.trackContainer.getTop() + this.trackContainer.getTranslationY();
         float rowTop = trackY + (float)holder.top + holder.row.getTranslationY();
         float rowBottom = trackY + (float)holder.bottom + holder.row.getTranslationY();
         if (rowBottom <= (float)viewportTop || rowTop >= (float)viewportBottom) {
            return 0.0F;
         } else {
            float rowCenter = (rowTop + rowBottom) * 0.5F;
            float alpha = 1.0F;
            // Centred, passed lines reach the top edge too: it fades as softly as the bottom.
            float fadeTopPx = this.centerAnchor ? this.edgeFadeBottomPx : this.edgeFadeTopPx;
            if (fadeTopPx > 1.0F && rowCenter < (float)viewportTop + fadeTopPx) {
               alpha *= this.clampAlpha((rowCenter - (float)viewportTop) / fadeTopPx);
            }

            if (this.edgeFadeBottomPx > 1.0F && rowCenter > (float)viewportBottom - this.edgeFadeBottomPx) {
               alpha *= this.clampAlpha(((float)viewportBottom - rowCenter) / this.edgeFadeBottomPx);
            }

            return this.clampAlpha(alpha);
         }
      }
   }

   private boolean isRowNearRenderViewport(@NonNull RowHolder holder) {
      int viewportTop = this.getPaddingTop();
      int viewportBottom = this.getHeight() - this.getPaddingBottom();
      if (viewportBottom <= viewportTop) {
         return true;
      }

      float rowUnitPx = (float)Math.max(1, Math.max(this.rowHeightPx, this.translationRowHeightPx));
      float extraPx = Math.max(this.blurViewportExtraPx, rowUnitPx * ROW_RENDER_VIEWPORT_EXTRA_ROWS);
      float trackY = (float)this.trackContainer.getTop() + this.trackContainer.getTranslationY();
      float rowTop = trackY + (float)holder.top + holder.row.getTranslationY();
      float rowBottom = trackY + (float)holder.bottom + holder.row.getTranslationY();
      return rowBottom >= (float)viewportTop - extraPx && rowTop <= (float)viewportBottom + extraPx;
   }

   private boolean shouldRefreshRowBlurStateOnFrame(@Nullable CollapsedKaraokeSpan collapsedSpan) {
      if (!this.disableBlurForCurrentContent && this.lyricsDepthBlurEnabled && !this.forceDisableRowBlur && !this.progressScrubbingActive && !this.manualScrollNoBlur) {
         if (collapsedSpan != null || this.sourceAlphaTransitionActive || this.trackAnimator != null && this.trackAnimator.isRunning()) {
            return true;
         } else {
            return this.translationToggleAnimator != null;
         }
      } else {
         return false;
      }
   }

   private void refreshRowBlurState() {
      this.refreshRowBlurState(this.resolveCollapsedKaraokeSpan(this.resolveRenderPositionMs()));
   }

   private void refreshRowBlurState(@Nullable CollapsedKaraokeSpan collapsedSpan) {
      boolean allowBlur = !this.disableBlurForCurrentContent && this.lyricsDepthBlurEnabled && !this.forceDisableRowBlur && !this.manualScrollNoBlur && !this.progressScrubbingActive;

      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         holder.row.setRowBlurRadiusPx(this.resolveRowBlurRadiusPx(holder, allowBlur, collapsedSpan));
      }

   }

   private void refreshActiveKaraokeHighlight(long renderPositionMs) {
      this.refreshKaraokeHighlightForSource(this.activeSourceIndex, renderPositionMs);
      int finishingSourceIndex = this.resolveFinishingKaraokeSourceIndex(renderPositionMs);
      if (finishingSourceIndex >= 0 && finishingSourceIndex != this.activeSourceIndex) {
         this.refreshKaraokeHighlightForSource(
               finishingSourceIndex,
               this.resolveFinishingKaraokeRenderPositionMs(finishingSourceIndex, renderPositionMs),
               renderPositionMs
         );
      }

      this.refreshCollapsedKaraokeSpanHighlights(renderPositionMs, finishingSourceIndex);
      this.refreshTrailingKaraokeGlyphLifts(renderPositionMs, finishingSourceIndex);
   }

   private void refreshTrailingKaraokeGlyphLifts(long renderPositionMs, int finishingSourceIndex) {
      if (this.activeSourceIndex <= 0) {
         return;
      }

      int firstSourceIndex = Math.max(0, this.activeSourceIndex - KARAOKE_GLYPH_LIFT_TRAILING_SOURCE_WINDOW);
      for(int sourceIndex = this.activeSourceIndex - 1; sourceIndex >= firstSourceIndex; --sourceIndex) {
         if (sourceIndex != finishingSourceIndex && this.hasSourceKaraokeGlyphLiftFrame(sourceIndex, renderPositionMs)) {
            this.refreshKaraokeHighlightForSource(sourceIndex, renderPositionMs);
         }
      }
   }

   private void refreshCollapsedKaraokeSpanHighlights(long renderPositionMs, int finishingSourceIndex) {
      CollapsedKaraokeSpan span = this.resolveCollapsedKaraokeSpan(renderPositionMs);
      if (span == null) {
         return;
      }

      for(int sourceIndex = span.anchorIndex; sourceIndex <= span.lastSkippedIndex; ++sourceIndex) {
         if (sourceIndex != this.activeSourceIndex && sourceIndex != finishingSourceIndex) {
            this.refreshKaraokeHighlightForSource(sourceIndex, renderPositionMs);
         }
      }
   }

   private void refreshKaraokeHighlightForSource(int sourceIndex, long renderPositionMs) {
      this.refreshKaraokeHighlightForSource(sourceIndex, renderPositionMs, renderPositionMs);
   }

   private void refreshKaraokeHighlightForSource(int sourceIndex, long highlightPositionMs, long liftPositionMs) {
      if (!this.timedLines.isEmpty() && sourceIndex >= 0 && sourceIndex < this.timedLines.size() && sourceIndex < this.sourceGroups.size()) {
         TimedLine activeLine = (TimedLine)this.timedLines.get(sourceIndex);
         if (!activeLine.prepCountdown) {
            KaraokeTimeline mainTimeline = activeLine.karaokeTimeline;
            KaraokeTimeline translationTimeline = sourceIndex < this.timedTranslationTimelines.size() ? (KaraokeTimeline)this.timedTranslationTimelines.get(sourceIndex) : null;
            if (mainTimeline != null || translationTimeline != null) {
               SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
               boolean useHdrHighlight = this.shouldUseHdrHighlightColors();
               float hdrProgress = useHdrHighlight ? this.resolveSourceHdrHighlightProgress(sourceIndex) : 0.0F;
               boolean useSourceHdrHighlight = useHdrHighlight && hdrProgress > 0.001F;
               if (mainTimeline != null) {
                  int activeColor = this.withColorAlpha(this.activeTextColor, 1.0F);
                  int pendingColor = this.withColorAlpha(this.activeTextColor, KARAOKE_STANDBY_ALPHA);
                  this.applyKaraokeTimelineToRows(group.firstMainRowIndex, group.mainRowCount, false, mainTimeline, highlightPositionMs, liftPositionMs, activeColor, pendingColor, useSourceHdrHighlight, this.hdrHighlightTextColor(1.0F, hdrProgress, this.hdrWhiteRatio), this.hdrHighlightTextColor(KARAOKE_STANDBY_ALPHA, hdrProgress, 1.0F));
               }

               if (this.translationVisible && translationTimeline != null && group.firstTranslationRowIndex >= 0 && group.translationRowCount > 0) {
                  float pendingTranslationAlpha = KARAOKE_STANDBY_ALPHA * TRANSLATION_ALPHA;
                  int activeColor = this.withColorAlpha(this.activeTextColor, TRANSLATION_ALPHA);
                  int pendingColor = this.withColorAlpha(this.activeTextColor, pendingTranslationAlpha);
                  this.applyKaraokeTimelineToRows(group.firstTranslationRowIndex, group.translationRowCount, true, translationTimeline, highlightPositionMs, liftPositionMs, activeColor, pendingColor, useSourceHdrHighlight, this.hdrHighlightTextColor(TRANSLATION_ALPHA, hdrProgress, this.hdrWhiteRatio), this.hdrHighlightTextColor(pendingTranslationAlpha, hdrProgress, 1.0F));
               }

            }
         }
      }
   }

   private long resolveFinishingKaraokeRenderPositionMs(int finishingSourceIndex, long renderPositionMs) {
      int nextSourceIndex = finishingSourceIndex + 1;
      if (nextSourceIndex != this.activeSourceIndex || nextSourceIndex >= this.sourceGroups.size()) {
         return renderPositionMs;
      }

      SourceGroup nextGroup = (SourceGroup)this.sourceGroups.get(nextSourceIndex);
      long advanceMs = this.resolveKaraokeScrollAdvanceMs(finishingSourceIndex, nextSourceIndex);
      if (advanceMs <= 0L || nextGroup.timeMs <= 0L) {
         return renderPositionMs;
      }

      TimedLine finishingLine = finishingSourceIndex < this.timedLines.size() ? (TimedLine)this.timedLines.get(finishingSourceIndex) : null;
      KaraokeTimeline mainTimeline = finishingLine != null ? finishingLine.karaokeTimeline : null;
      KaraokeTimeline translationTimeline = finishingSourceIndex < this.timedTranslationTimelines.size() ? (KaraokeTimeline)this.timedTranslationTimelines.get(finishingSourceIndex) : null;
      long motionEndMs = Math.max(this.resolveKaraokeTimelineMotionEndMs(mainTimeline), this.resolveKaraokeTimelineMotionEndMs(translationTimeline));
      if (motionEndMs <= renderPositionMs || motionEndMs <= nextGroup.timeMs) {
         return renderPositionMs;
      }

      long handoffStartMs = nextGroup.timeMs - advanceMs;
      float handoffProgress = this.clampAlpha((float)(renderPositionMs - handoffStartMs) / (float)Math.max(1L, advanceMs));
      return Math.max(renderPositionMs, renderPositionMs + (long)Math.round((float)(motionEndMs - renderPositionMs) * handoffProgress));
   }

   private void applyKaraokeTimelineToRows(int firstRowIndex, int rowCount, boolean translation, @NonNull KaraokeTimeline timeline, long highlightPositionMs, long liftPositionMs, int revealedColor, int pendingColor, boolean useHdrHighlight, long revealedHdrColor, long pendingHdrColor) {
      if (rowCount > 0) {
         int tokenCount = Math.min(timeline.tokenTimeMs.length, timeline.tokenEndCodePoint.length);
         if (tokenCount > 0) {
            for(int rowIndex = 0; rowIndex < rowCount; ++rowIndex) {
               int holderIndex = firstRowIndex + rowIndex;
               if (holderIndex >= 0 && holderIndex < this.rowHolders.size()) {
                  RowHolder holder = (RowHolder)this.rowHolders.get(holderIndex);
                  if (!holder.prepCountdown && holder.translation == translation && holder.lineCodePointCount > 0) {
                     boolean allowBackstepStabilization = this.playbackRunning && !this.progressScrubbingActive;
                     holder.row.setKaraokeHighlight(timeline, holder.lineCodePointStart, holder.lineCodePointCount, highlightPositionMs, liftPositionMs, revealedColor, pendingColor, useHdrHighlight, revealedHdrColor, pendingHdrColor, 0.0F, allowBackstepStabilization);
                  }
               }
            }

         }
      }
   }

   private void clearCachedKaraokeHighlightState() {
   }

   private float resolveSourceAlpha(int sourceIndex, long renderPositionMs) {
      return this.resolveSourceAlpha(sourceIndex, renderPositionMs, this.resolveCollapsedKaraokeSpan(renderPositionMs));
   }

   private float resolveSourceAlpha(int sourceIndex, long renderPositionMs, @Nullable CollapsedKaraokeSpan collapsedSpan) {
      if (sourceIndex < 0) {
         return NON_TARGET_ALPHA;
      } else {
         if (this.sourceAlphaTransitionActive) {
            if (sourceIndex == this.sourceAlphaTransitionFrom) {
               float outgoingEndAlpha = this.resolveStandbySourceAlpha(sourceIndex);
               if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size() && ((SourceGroup)this.sourceGroups.get(sourceIndex)).prepCountdown) {
                  outgoingEndAlpha = 0.0F;
               }

               float outgoingProgress = this.clampAlpha(this.sourceAlphaTransitionProgress / 0.62F);
               return this.lerpAlpha(this.sourceAlphaTransitionOutgoingStart, outgoingEndAlpha, outgoingProgress);
            }

            if (sourceIndex == this.sourceAlphaTransitionTo) {
               return this.lerpAlpha(this.sourceAlphaTransitionIncomingStart, 1.0F, this.sourceAlphaTransitionProgress);
            }

            float collapsedAlpha = this.resolveCollapsedKaraokeSourceAlpha(sourceIndex, renderPositionMs, collapsedSpan);
            if (!Float.isNaN(collapsedAlpha)) {
               return collapsedAlpha;
            }
         }

         if (sourceIndex == this.visualSourceIndex) {
            return 1.0F;
         } else if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size() && ((SourceGroup)this.sourceGroups.get(sourceIndex)).prepCountdown) {
            return 0.0F;
         } else {
            float alpha = this.resolveStandbySourceAlpha(sourceIndex);
            float collapsedAlpha = this.resolveCollapsedKaraokeSourceAlpha(sourceIndex, renderPositionMs, collapsedSpan);
            if (!Float.isNaN(collapsedAlpha)) {
               alpha = Math.max(alpha, collapsedAlpha);
            }

            int nextIndex = this.visualSourceIndex + 1;
            if (sourceIndex == nextIndex) {
               alpha = Math.max(alpha, this.resolvePreparedNextAlpha(renderPositionMs));
            }

            return this.clampAlpha(alpha);
         }
      }
   }

   private float resolveCollapsedKaraokeSourceAlpha(int sourceIndex, long renderPositionMs, @Nullable CollapsedKaraokeSpan span) {
      if (span == null || sourceIndex <= span.anchorIndex || sourceIndex > span.lastSkippedIndex || sourceIndex >= this.sourceGroups.size()) {
         return Float.NaN;
      }

      SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
      if (group.prepCountdown || group.timeMs < 0L) {
         return Float.NaN;
      }

      long sourceTimeMs = group.timeMs;
      if (renderPositionMs >= sourceTimeMs) {
         return 1.0F;
      }

      int previousIndex = sourceIndex - 1;
      long previousTimeMs = previousIndex >= 0 && previousIndex < this.sourceGroups.size() ? ((SourceGroup)this.sourceGroups.get(previousIndex)).timeMs : Long.MIN_VALUE;
      long advanceMs = this.resolveKaraokeScrollAdvanceMs(previousIndex, sourceIndex);
      long fadeStartMs = sourceTimeMs - Math.max(1L, advanceMs);
      if (previousTimeMs >= 0L) {
         fadeStartMs = Math.max(previousTimeMs, fadeStartMs);
      }

      if (renderPositionMs <= fadeStartMs) {
         return KARAOKE_STANDBY_ALPHA;
      }

      float progress = this.clampAlpha((float)(renderPositionMs - fadeStartMs) / (float)Math.max(1L, sourceTimeMs - fadeStartMs));
      return this.lerpAlpha(KARAOKE_STANDBY_ALPHA, 1.0F, progress);
   }

   private float resolveSourceHdrHighlightProgress(int sourceIndex) {
      if (!this.shouldUseHdrHighlightColors() || sourceIndex < 0) {
         return 0.0F;
      }

      if (this.sourceAlphaTransitionActive) {
         if (sourceIndex == this.sourceAlphaTransitionFrom) {
            return 1.0F - this.clampAlpha(this.sourceAlphaTransitionProgress / 0.62F);
         }

         if (sourceIndex == this.sourceAlphaTransitionTo) {
            return this.clampAlpha(this.sourceAlphaTransitionProgress);
         }

         return 0.0F;
      }

      return sourceIndex == this.visualSourceIndex ? 1.0F : 0.0F;
   }

   /**
    * How far a line above the current one has gone (alpha and scale): the line just passed goes
    * with the scroll, and every passed line stays gone afterwards. Before, a passed line came back
    * at the end of the transition; on a phone it was off screen by then, on a tall Pad lyrics
    * column it visibly reappeared. Browsing (manual scroll, scrubbing) shows every line.
    */
   private float resolvePassedSourceVisualProgress(int sourceIndex) {
      if (this.centerAnchor) {
         // Centred: passed lines stay, as the lines ahead do.
         return 0.0F;
      }
      float hidden = this.resolvePassedSourceHiddenProgress(sourceIndex);
      // Browsing (a manual scroll or scrubbing) brings passed lines back, and they leave again
      // once the lyrics resume following the song, both eased (see syncPassedReveal).
      return hidden * (1.0F - this.passedRevealProgress);
   }

   /**
    * Browsing shows every line: passed lines come back over PASSED_REVEAL_IN_MS when a manual
    * scroll or scrubbing starts, and go again over PASSED_REVEAL_OUT_MS when it ends.
    */
   private void syncPassedReveal() {
      float target = this.manualScrollActive || this.progressScrubbingActive ? 1.0F : 0.0F;
      if (Math.abs(this.passedRevealTarget - target) < 0.001F) {
         return;
      }
      this.passedRevealTarget = target;
      if (this.passedRevealAnimator != null) {
         this.passedRevealAnimator.cancel();
      }
      float distance = Math.abs(target - this.passedRevealProgress);
      if (distance < 0.001F || !this.isAttachedToWindow()) {
         this.passedRevealProgress = target;
         this.refreshPassedRowsForReveal();
         return;
      }
      final ValueAnimator animator = com.lab.hv.RustValueAnimator.ofFloat(this.passedRevealProgress, target);
      long fullMs = target > 0.5F ? PASSED_REVEAL_IN_MS : PASSED_REVEAL_OUT_MS;
      animator.setDuration(Math.max(80L, Math.round((float)fullMs * distance)));
      animator.setInterpolator(new android.view.animation.PathInterpolator(0.25F, 0.1F, 0.25F, 1.0F));
      animator.addUpdateListener((a) -> {
         if (this.passedRevealAnimator != animator) {
            return;
         }
         this.passedRevealProgress = (Float)a.getAnimatedValue();
         this.refreshPassedRowsForReveal();
      });
      this.passedRevealAnimator = animator;
      animator.start();
   }

   private void refreshPassedRowsForReveal() {
      this.refreshRowAlphaState(this.resolveRenderPositionMs());
      this.refreshRowContentPresentationScale();
      this.invalidate();
   }

   private float resolvePassedSourceHiddenProgress(int sourceIndex) {
      if (sourceIndex < 0) {
         return 0.0F;
      }

      if (this.sourceAlphaTransitionActive) {
         int passedSourceIndex = this.resolvePassedSourceIndexForTarget(this.sourceAlphaTransitionTo);
         if (sourceIndex == passedSourceIndex) {
            return this.trackScrollInterpolator.getInterpolation(this.clampAlpha(this.sourceAlphaTransitionProgress));
         }

         return passedSourceIndex >= 0 && sourceIndex < passedSourceIndex ? 1.0F : 0.0F;
      }

      return this.visualSourceIndex >= 0 && sourceIndex < this.visualSourceIndex ? 1.0F : 0.0F;
   }

   private static final long PASSED_REVEAL_IN_MS = 300L;
   private static final long PASSED_REVEAL_OUT_MS = 420L;
   /** 1 while browsing: passed lines shown; 0 following the song: passed lines gone. */
   private float passedRevealProgress;
   private float passedRevealTarget;
   @Nullable
   private ValueAnimator passedRevealAnimator;

   private int resolvePassedSourceIndexForTarget(int targetSourceIndex) {
      int passedSourceIndex = targetSourceIndex - 1;
      return passedSourceIndex >= 0 && passedSourceIndex < this.sourceGroups.size() ? passedSourceIndex : -1;
   }

   private float resolvePrepRowVisibilityAlpha(int sourceIndex, long targetTimeMs, long renderPositionMs) {
      long startMs = this.resolvePrepCountdownStartMs(sourceIndex, targetTimeMs);
      if (startMs < 0L) {
         return 1.0F;
      } else if (renderPositionMs >= targetTimeMs) {
         return 0.0F;
      } else {
         long totalDurationMs = Math.max(1L, targetTimeMs - startMs);
         long finalWindowMs = Math.min(1600L, Math.max(900L, totalDurationMs / 3L));
         long vanishLeadMs = Math.max(260L, finalWindowMs / 3L);
         long vanishStartMs = targetTimeMs - vanishLeadMs;
         return renderPositionMs <= vanishStartMs ? 1.0F : 1.0F - this.clampAlpha((float)(renderPositionMs - vanishStartMs) / (float)Math.max(1L, targetTimeMs - vanishStartMs));
      }
   }

   private float resolvePreparedNextAlpha(long renderPositionMs) {
      if (this.visualSourceIndex >= 0 && this.visualSourceIndex + 1 < this.sourceGroups.size()) {
         SourceGroup activeGroup = (SourceGroup)this.sourceGroups.get(this.visualSourceIndex);
         if (activeGroup.prepCountdown) {
            return NON_TARGET_ALPHA;
         } else {
            TimedLine activeLine = this.visualSourceIndex < this.timedLines.size() ? (TimedLine)this.timedLines.get(this.visualSourceIndex) : null;
            KaraokeTimeline activeMainTimeline = activeLine != null ? activeLine.karaokeTimeline : null;
            KaraokeTimeline activeTranslationTimeline = this.visualSourceIndex < this.timedTranslationTimelines.size() ? (KaraokeTimeline)this.timedTranslationTimelines.get(this.visualSourceIndex) : null;
            if (activeMainTimeline == null && activeTranslationTimeline == null) {
               long activeStamp = activeGroup.timeMs;
               long nextStamp = ((SourceGroup)this.sourceGroups.get(this.visualSourceIndex + 1)).timeMs;
               if (activeStamp >= 0L && nextStamp > activeStamp) {
                  long windowStart = activeStamp + (long)Math.round((float)(nextStamp - activeStamp) * 0.5F);
                  if (renderPositionMs <= windowStart) {
                     return NON_TARGET_ALPHA;
                  } else if (renderPositionMs >= nextStamp) {
                     return 0.6F;
                  } else {
                     float t = (float)(renderPositionMs - windowStart) / (float)Math.max(1L, nextStamp - windowStart);
                     return this.lerpAlpha(NON_TARGET_ALPHA, 0.6F, t);
                  }
               } else {
                  return NON_TARGET_ALPHA;
               }
            } else {
               return KARAOKE_STANDBY_ALPHA;
            }
         }
      } else {
         return NON_TARGET_ALPHA;
      }
   }

   private float resolveStandbySourceAlpha(int sourceIndex) {
      float base = this.isKaraokeSource(sourceIndex) ? KARAOKE_STANDBY_ALPHA : NON_TARGET_ALPHA;
      return base * this.resolveDistanceAlphaFactor(sourceIndex);
   }

   /**
    * Lines further from the one being sung are fainter: the next line keeps the standby alpha,
    * every line beyond it a step less, down to a floor. The distance is measured from where the
    * scroll is (between the two lines mid-transition), so nothing steps when the line changes;
    * browsing the lyrics (manual scroll, scrubbing) evens them out again.
    */
   private float resolveDistanceAlphaFactor(int sourceIndex) {
      float focus;
      if (this.sourceAlphaTransitionActive && this.sourceAlphaTransitionFrom >= 0 && this.sourceAlphaTransitionTo >= 0) {
         focus = this.sourceAlphaTransitionFrom + (this.sourceAlphaTransitionTo - this.sourceAlphaTransitionFrom) * this.clampAlpha(this.sourceAlphaTransitionProgress);
      } else if (this.visualSourceIndex >= 0) {
         focus = (float)this.visualSourceIndex;
      } else if (this.activeSourceIndex >= 0) {
         focus = (float)this.activeSourceIndex;
      } else {
         // Before the first line: it waits where the sung line sits.
         focus = -1.0F;
      }
      float distance = Math.abs((float)sourceIndex - focus);
      float steps = Math.max(0.0F, distance - 1.0F);
      float factor = Math.max(DISTANCE_ALPHA_FLOOR, (float)Math.pow(DISTANCE_ALPHA_STEP, steps));
      return factor + (1.0F - factor) * this.clampAlpha(this.passedRevealProgress);
   }

   private float resolveCurrentDisplayedMainRowAlpha(int sourceIndex, long renderPositionMs) {
      if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
         if (group.firstMainRowIndex >= 0 && group.firstMainRowIndex < this.rowHolders.size()) {
            RowHolder holder = (RowHolder)this.rowHolders.get(group.firstMainRowIndex);
            return this.clampAlpha(holder.row.getAlpha());
         } else {
            return this.clampAlpha(this.resolveSourceAlpha(sourceIndex, renderPositionMs));
         }
      } else {
         return this.clampAlpha(this.resolveSourceAlpha(sourceIndex, renderPositionMs));
      }
   }

   private float resolveSourceTransitionIncomingStartAlpha(int sourceIndex, long renderPositionMs) {
      return this.isKaraokeSource(sourceIndex) ? 1.0F : this.resolveCurrentDisplayedMainRowAlpha(sourceIndex, renderPositionMs);
   }

   private int resolveTransitionHandoffSourceIndex() {
      if (this.sourceAlphaTransitionActive && this.trackAnimator != null && this.trackAnimator.isRunning() && this.sourceAlphaTransitionTo >= 0 && this.sourceAlphaTransitionTo < this.sourceGroups.size()) {
         return this.sourceAlphaTransitionTo;
      } else {
         return this.resolveSettledSourceIndex();
      }
   }

   private boolean isKaraokeSource(int sourceIndex) {
      if (sourceIndex >= 0 && sourceIndex < this.timedLines.size()) {
         TimedLine line = (TimedLine)this.timedLines.get(sourceIndex);
         if (line.karaokeTimeline != null) {
            return true;
         } else {
            return sourceIndex < this.timedTranslationTimelines.size() && this.timedTranslationTimelines.get(sourceIndex) != null;
         }
      } else {
         return false;
      }
   }

   private void refreshKaraokeGlyphLiftConfiguration() {
      for(int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         boolean karaoke = this.resolveKaraokeTimelineForRow(holder) != null;
         holder.row.setKaraokeGlyphPresentationPx(
               karaoke ? this.karaokeGlyphRestOffsetPx : 0.0F,
               karaoke ? this.karaokeGlyphLongNoteExtraLiftPx : 0.0F,
               karaoke ? this.karaokeGlyphLongNoteBouncePx : 0.0F,
               karaoke ? this.karaokeGlyphLongNoteGlowInnerRadiusPx : 0.0F,
               karaoke ? this.karaokeGlyphLongNoteGlowOuterRadiusPx : 0.0F
         );
      }
   }

   @Nullable
   private KaraokeTimeline resolveKaraokeTimelineForRow(@NonNull RowHolder holder) {
      if (holder.prepCountdown || holder.sourceIndex < 0 || holder.sourceIndex >= this.timedLines.size()) {
         return null;
      }

      if (holder.translation) {
         return holder.sourceIndex < this.timedTranslationTimelines.size() ? (KaraokeTimeline)this.timedTranslationTimelines.get(holder.sourceIndex) : null;
      }

      return ((TimedLine)this.timedLines.get(holder.sourceIndex)).karaokeTimeline;
   }

   private float resolveRowBlurRadiusPx(@NonNull RowHolder holder, boolean allowBlur) {
      return this.resolveRowBlurRadiusPx(holder, allowBlur, this.resolveCollapsedKaraokeSpan(this.resolveRenderPositionMs()));
   }

   private float resolveRowBlurRadiusPx(@NonNull RowHolder holder, boolean allowBlur, @Nullable CollapsedKaraokeSpan collapsedSpan) {
      if (allowBlur && holder.sourceIndex >= 0 && this.isRowNearBlurViewport(holder)) {
         float distance;
         if (collapsedSpan != null) {
            distance = this.resolveBlurSourceDistance(holder.sourceIndex, 0.0F, collapsedSpan);
         } else {
            float focusSourcePosition = this.resolveAnimatedFocusSourcePosition();
            if (focusSourcePosition < 0.0F) {
               return 0.0F;
            }

            distance = this.resolveBlurSourceDistance(holder.sourceIndex, focusSourcePosition, null);
         }

         if (distance <= 0.001F) {
            return 0.0F;
         } else {
            float rawRadiusPx = Math.min(this.rowBlurMaxPx, this.resolveSmoothBlurDistance(distance) * this.rowBlurStepPx);
            return this.quantizeBlurRadiusPx(rawRadiusPx);
         }
      } else {
         return 0.0F;
      }
   }

   private float resolveBlurSourceDistance(int sourceIndex, float focusSourcePosition, @Nullable CollapsedKaraokeSpan collapsedSpan) {
      if (collapsedSpan != null && collapsedSpan.anchorIndex >= 0 && collapsedSpan.targetIndex >= collapsedSpan.anchorIndex) {
         if (sourceIndex >= collapsedSpan.anchorIndex && sourceIndex <= collapsedSpan.targetIndex) {
            return 0.0F;
         }

         if (sourceIndex < collapsedSpan.anchorIndex) {
            return (float)(collapsedSpan.anchorIndex - sourceIndex);
         }

         return (float)(sourceIndex - collapsedSpan.targetIndex);
      }

      return Math.abs((float)sourceIndex - focusSourcePosition);
   }

   private float resolveAnimatedFocusSourcePosition() {
      return this.sourceAlphaTransitionActive && this.sourceAlphaTransitionFrom >= 0 && this.sourceAlphaTransitionTo >= 0 && this.sourceAlphaTransitionFrom != this.sourceAlphaTransitionTo ? (float)this.sourceAlphaTransitionFrom + (float)(this.sourceAlphaTransitionTo - this.sourceAlphaTransitionFrom) * this.sourceAlphaTransitionProgress : (float)this.resolveSettledSourceIndex();
   }

   private float resolveSmoothBlurDistance(float distance) {
      if (distance <= 1.0F) {
         float t = this.clampAlpha(distance);
         return t * t * (3.0F - 2.0F * t);
      } else {
         return distance;
      }
   }

   private boolean isRowNearBlurViewport(@NonNull RowHolder holder) {
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      if (innerHeight <= 0) {
         return true;
      } else {
         float trackTranslationY = this.trackContainer.getTranslationY();
         float top = (float)holder.top + trackTranslationY;
         float bottom = (float)holder.bottom + trackTranslationY;
         return bottom >= -this.blurViewportExtraPx && top <= (float)innerHeight + this.blurViewportExtraPx;
      }
   }

   private float quantizeBlurRadiusPx(float radiusPx) {
      if (radiusPx <= 0.01F) {
         return 0.0F;
      } else {
         float quantum = Math.max(0.01F, this.blurRadiusQuantizePx);
         return Math.min(this.rowBlurMaxPx, (float)Math.round(radiusPx / quantum) * quantum);
      }
   }

   private void beginSourceAlphaTransition(int fromSource, int toSource, float outgoingStartAlpha, float incomingStartAlpha) {
      this.sourceAlphaTransitionFrom = fromSource;
      this.sourceAlphaTransitionTo = toSource;
      this.sourceAlphaTransitionProgress = 0.0F;
      this.sourceAlphaTransitionOutgoingStart = this.clampAlpha(outgoingStartAlpha);
      this.sourceAlphaTransitionIncomingStart = this.clampAlpha(incomingStartAlpha);
      this.sourceAlphaTransitionActive = fromSource >= 0 && toSource >= 0 && fromSource != toSource;
   }

   private void updateSourceAlphaTransitionProgress(float progress) {
      if (this.sourceAlphaTransitionActive) {
         this.sourceAlphaTransitionProgress = this.clampAlpha(progress);
      }
   }

   private void clearSourceAlphaTransition() {
      this.sourceAlphaTransitionActive = false;
      this.sourceAlphaTransitionFrom = -1;
      this.sourceAlphaTransitionTo = -1;
      this.sourceAlphaTransitionProgress = 1.0F;
      this.sourceAlphaTransitionOutgoingStart = 1.0F;
      this.sourceAlphaTransitionIncomingStart = 0.6F;
   }

   private float clampAlpha(float alpha) {
      if (alpha < 0.0F) {
         return 0.0F;
      } else {
         return alpha > 1.0F ? 1.0F : alpha;
      }
   }

   private float lerpAlpha(float start, float end, float t) {
      float clamped = this.clampAlpha(t);
      return start + (end - start) * clamped;
   }

   private int withColorAlpha(int color, float alphaRatio) {
      float a = alphaRatio;
      if (alphaRatio < 0.0F) {
         a = 0.0F;
      }

      if (a > 1.0F) {
         a = 1.0F;
      }

      int alpha = Math.round((float)(color >>> 24 & 255) * a);
      return color & 16777215 | alpha << 24;
   }

   private boolean shouldUseHdrHighlightColors() {
      return this.darkMode && this.hdrHighlightEnabled && VERSION.SDK_INT >= 29;
   }

   private long hdrWhiteColorWithAlpha(float alphaRatio) {
      if (!this.shouldUseHdrHighlightColors()) {
         return Color.pack(this.withColorAlpha(this.activeTextColor, alphaRatio));
      } else {
         float alpha = this.clampAlpha(alphaRatio);
         return HdrColor.linearExtendedWhite(this.hdrWhiteRatio, alpha);
      }
   }

   private long hdrSdrWhiteColorWithAlpha(float alphaRatio) {
      if (!this.shouldUseHdrHighlightColors()) {
         return Color.pack(this.withColorAlpha(this.activeTextColor, alphaRatio));
      } else {
         float alpha = this.clampAlpha(alphaRatio);
         return HdrColor.linearExtendedWhite(1.0F, alpha);
      }
   }

   private long hdrHighlightTextColor(float alphaRatio, float hdrProgress, float targetWhiteRatio) {
      int baseColor = this.withColorAlpha(this.activeTextColor, alphaRatio);
      if (!this.shouldUseHdrHighlightColors()) {
         return Color.pack(baseColor);
      }

      return HdrColor.blendTowardLinearExtendedWhite(baseColor, this.clampAlpha(hdrProgress), targetWhiteRatio);
   }

   private int resolveCurrentSourceIndex(long positionMs) {
      if (this.sourceGroups.isEmpty()) {
         return -1;
      } else if (this.timedLines.isEmpty()) {
         return Math.max(0, Math.min(this.resolveSettledSourceIndex(), this.sourceGroups.size() - 1));
      } else {
         int answer = this.resolveTimestampSourceIndex(positionMs);

         if (answer >= 0) {
            int collapsedIndex = this.resolveCollapsedKaraokeScrollIndex(answer, positionMs);
            if (collapsedIndex >= 0) {
               return collapsedIndex;
            }

            int nextIndex = answer + 1;
            if (nextIndex < this.sourceGroups.size()) {
               long nextStamp = ((SourceGroup)this.sourceGroups.get(nextIndex)).timeMs;
               long advanceMs = this.resolveKaraokeScrollAdvanceMs(answer, nextIndex);
               if (advanceMs > 0L && positionMs >= nextStamp - advanceMs) {
                  return nextIndex;
               }
            }
         }

         return answer;
      }
   }

   private int resolveTimestampSourceIndex(long positionMs) {
      int low = 0;
      int high = this.sourceGroups.size() - 1;
      int answer = -1;

      while(low <= high) {
         int mid = low + high >>> 1;
         long stamp = ((SourceGroup)this.sourceGroups.get(mid)).timeMs;
         if (stamp <= positionMs) {
            answer = mid;
            low = mid + 1;
         } else {
            high = mid - 1;
         }
      }

      return answer;
   }

   private long resolveKaraokeScrollAdvanceMs(int currentSourceIndex, int nextSourceIndex) {
      if (currentSourceIndex >= 0 && nextSourceIndex > currentSourceIndex && nextSourceIndex < this.sourceGroups.size() && this.isKaraokeSource(nextSourceIndex)) {
         long currentStamp = ((SourceGroup)this.sourceGroups.get(currentSourceIndex)).timeMs;
         long nextStamp = ((SourceGroup)this.sourceGroups.get(nextSourceIndex)).timeMs;
         if (currentStamp >= 0L && nextStamp > currentStamp) {
            long gapMs = nextStamp - currentStamp;
            long curveSyncedAdvanceMs = Math.min(
                  this.karaokeScrollCurveAdvanceMs,
                  Math.max(0L, gapMs / 2L)
            );
            long currentMotionEndMs = this.resolveKaraokeSourceMotionEndMs(currentSourceIndex);
            return KaraokeScrollTimingPolicy.resolveAdvanceMs(
                  currentStamp,
                  nextStamp,
                  currentMotionEndMs,
                  curveSyncedAdvanceMs,
                  TRACK_SCROLL_ANIM_MS
            );
         } else {
            return 0L;
         }
      } else {
         return 0L;
      }
   }

   private long resolveKaraokeSourceMotionEndMs(int sourceIndex) {
      if (!this.isKaraokeSource(sourceIndex)) {
         return Long.MIN_VALUE;
      }

      TimedLine line = sourceIndex < this.timedLines.size()
            ? (TimedLine)this.timedLines.get(sourceIndex)
            : null;
      KaraokeTimeline mainTimeline = line == null ? null : line.karaokeTimeline;
      KaraokeTimeline translationTimeline = sourceIndex < this.timedTranslationTimelines.size()
            ? (KaraokeTimeline)this.timedTranslationTimelines.get(sourceIndex)
            : null;
      return Math.max(
            this.resolveKaraokeTimelineMotionEndMs(mainTimeline),
            this.resolveKaraokeTimelineMotionEndMs(translationTimeline)
      );
   }

   private int resolveCollapsedKaraokeScrollIndex(int timestampSourceIndex, long positionMs) {
      CollapsedKaraokeSpan span = this.resolveCollapsedKaraokeSpan(timestampSourceIndex, positionMs);
      return span == null ? -1 : (positionMs >= span.scrollStartMs ? span.targetIndex : span.anchorIndex);
   }

   @Nullable
   private CollapsedKaraokeSpan resolveCollapsedKaraokeSpan(long positionMs) {
      int timestampSourceIndex = this.resolveTimestampSourceIndex(positionMs);
      return timestampSourceIndex < 0 ? null : this.resolveCollapsedKaraokeSpan(timestampSourceIndex, positionMs);
   }

   @Nullable
   private CollapsedKaraokeSpan resolveCollapsedKaraokeSpan(int timestampSourceIndex, long positionMs) {
      CollapsedKaraokeSpan activeSpan = this.resolveCollapsedKaraokeSpanFromAnchor(this.activeSourceIndex, timestampSourceIndex, positionMs);
      if (activeSpan != null && positionMs < activeSpan.endTimeMs) {
         return activeSpan;
      }

      for(int skippedCount = KARAOKE_SCROLL_COLLAPSE_MAX_SKIPPED_SOURCES; skippedCount >= 1; --skippedCount) {
         int firstAnchor = Math.max(0, timestampSourceIndex - skippedCount);
         int lastAnchor = Math.min(timestampSourceIndex, this.sourceGroups.size() - skippedCount - 2);

         for(int anchorIndex = firstAnchor; anchorIndex <= lastAnchor; ++anchorIndex) {
            CollapsedKaraokeSpan span = this.resolveCollapsedKaraokeSpanFromAnchor(anchorIndex, timestampSourceIndex, positionMs);
            if (span != null && positionMs < span.endTimeMs) {
               return span;
            }
         }
      }

      return null;
   }

   @Nullable
   private CollapsedKaraokeSpan resolveCollapsedKaraokeSpanFromAnchor(int anchorIndex, int timestampSourceIndex, long positionMs) {
      if (anchorIndex < 0) {
         return null;
      }

      for(int skippedCount = KARAOKE_SCROLL_COLLAPSE_MAX_SKIPPED_SOURCES; skippedCount >= 1; --skippedCount) {
         if (this.isCollapsedKaraokeSpan(anchorIndex, skippedCount)) {
            int targetIndex = anchorIndex + skippedCount + 1;
            int lastSkippedIndex = anchorIndex + skippedCount;
            SourceGroup targetGroup = (SourceGroup)this.sourceGroups.get(targetIndex);
            long lastSkippedTimeMs = ((SourceGroup)this.sourceGroups.get(lastSkippedIndex)).timeMs;
            long foldedMotionEndMs = this.resolveKaraokeSpanMotionEndMs(anchorIndex, lastSkippedIndex);
            long gapMs = targetGroup.timeMs - lastSkippedTimeMs;
            long curveSyncedAdvanceMs = Math.min(
                  this.karaokeScrollCurveAdvanceMs,
                  Math.max(0L, gapMs / 2L)
            );
            long advanceMs = KaraokeScrollTimingPolicy.resolveAdvanceMs(
                  lastSkippedTimeMs,
                  targetGroup.timeMs,
                  foldedMotionEndMs,
                  curveSyncedAdvanceMs,
                  TRACK_SCROLL_ANIM_MS
            );
            long scrollStartMs = Math.max(targetGroup.timeMs - advanceMs, lastSkippedTimeMs);
            if (foldedMotionEndMs != Long.MIN_VALUE && foldedMotionEndMs > targetGroup.timeMs) {
               // Any folded row can still own visible glyph motion here, not just the last row.
               // Keep A/B/C alive until the longest real tail ends instead of clipping B because
               // a later, shorter row happened to be the chronological neighbour of the target.
               long settledTailMs = foldedMotionEndMs > Long.MAX_VALUE - KaraokeScrollTimingPolicy.MIN_POST_HIGHLIGHT_HOLD_MS
                     ? Long.MAX_VALUE
                     : foldedMotionEndMs + KaraokeScrollTimingPolicy.MIN_POST_HIGHLIGHT_HOLD_MS;
               scrollStartMs = Math.max(scrollStartMs, settledTailMs);
            }
            long endTimeMs = Math.max(targetGroup.timeMs, scrollStartMs);
            boolean timestampInsideFoldedRows = timestampSourceIndex >= anchorIndex
                  && timestampSourceIndex <= lastSkippedIndex;
            boolean waitingForOverlappingTail = timestampSourceIndex == targetIndex
                  && positionMs < scrollStartMs
                  && scrollStartMs > targetGroup.timeMs;
            if (timestampInsideFoldedRows || waitingForOverlappingTail) {
               return new CollapsedKaraokeSpan(
                     anchorIndex,
                     lastSkippedIndex,
                     targetIndex,
                     scrollStartMs,
                     targetGroup.timeMs,
                     endTimeMs
               );
            }
         }
      }

      return null;
   }

   private long resolveKaraokeSpanMotionEndMs(int firstSourceIndex, int lastSourceIndex) {
      long motionEndMs = Long.MIN_VALUE;
      int safeFirst = Math.max(0, firstSourceIndex);
      int safeLast = Math.min(lastSourceIndex, this.sourceGroups.size() - 1);
      for(int sourceIndex = safeFirst; sourceIndex <= safeLast; ++sourceIndex) {
         motionEndMs = Math.max(motionEndMs, this.resolveKaraokeSourceMotionEndMs(sourceIndex));
      }
      return motionEndMs;
   }

   private boolean isCollapsedKaraokeSpan(int anchorIndex, int skippedCount) {
      int targetIndex = anchorIndex + skippedCount + 1;
      if (anchorIndex < 0 || skippedCount <= 0 || skippedCount > KARAOKE_SCROLL_COLLAPSE_MAX_SKIPPED_SOURCES || targetIndex >= this.sourceGroups.size()) {
         return false;
      }

      for(int i = anchorIndex; i <= targetIndex; ++i) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(i);
         if (group.prepCountdown || group.timeMs < 0L || !this.isKaraokeSource(i)) {
            return false;
         }
      }

      for(int i = anchorIndex + 1; i <= anchorIndex + skippedCount; ++i) {
         if (!this.isCollapsedKaraokeSkippedSourceTooFast(i)) {
            return false;
         }
      }

      long skippedStamp = ((SourceGroup)this.sourceGroups.get(anchorIndex + skippedCount)).timeMs;
      long targetStamp = ((SourceGroup)this.sourceGroups.get(targetIndex)).timeMs;
      return targetStamp > skippedStamp && this.canFitCollapsedKaraokeSpan(anchorIndex, anchorIndex + skippedCount);
   }

   private boolean isCollapsedKaraokeSkippedSourceTooFast(int sourceIndex) {
      if (sourceIndex <= 0 || sourceIndex + 1 >= this.sourceGroups.size()) {
         return false;
      }

      long previousStamp = ((SourceGroup)this.sourceGroups.get(sourceIndex - 1)).timeMs;
      long stamp = ((SourceGroup)this.sourceGroups.get(sourceIndex)).timeMs;
      long nextStamp = ((SourceGroup)this.sourceGroups.get(sourceIndex + 1)).timeMs;
      if (previousStamp < 0L || stamp <= previousStamp || nextStamp <= stamp) {
         return false;
      }

      return KaraokeScrollTimingPolicy.shouldCollapseIntermediateLine(
            previousStamp,
            stamp,
            nextStamp,
            KARAOKE_SCROLL_COLLAPSE_GAP_MAX_MS
      );
   }

   private boolean canFitCollapsedKaraokeSpan(int firstSourceIndex, int lastSourceIndex) {
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      if (innerHeight <= 0 || firstSourceIndex < 0 || lastSourceIndex < firstSourceIndex || lastSourceIndex >= this.sourceGroups.size()) {
         return false;
      }

      SourceGroup firstGroup = (SourceGroup)this.sourceGroups.get(firstSourceIndex);
      SourceGroup lastGroup = (SourceGroup)this.sourceGroups.get(lastSourceIndex);
      if (firstGroup.firstRowIndex < 0 || lastGroup.lastRowIndex < 0 || lastGroup.lastRowIndex >= this.rowHolders.size()) {
         return false;
      }

      RowHolder lastRow = (RowHolder)this.rowHolders.get(lastGroup.lastRowIndex);
      float visibleTop = firstGroup.topYInTrack;
      float visibleBottom = (float)lastRow.bottom;
      float requiredHeight = Math.max(0.0F, visibleBottom - visibleTop);
      float anchorY = this.resolveSourceAnchorY(innerHeight, firstSourceIndex);
      float availableBelowAnchor = (float)innerHeight - anchorY - (float)this.rowHeightPx * 0.35F;
      return requiredHeight <= Math.max((float)this.rowHeightPx, availableBelowAnchor);
   }

   private void startTrackAnimatorTo(float startY, float targetY, @Nullable LagSession lagSession, int targetSourceIndex) {
      this.startTrackAnimatorTo(startY, targetY, lagSession, targetSourceIndex, this.resolveSettledSourceIndex());
   }

   private void startTrackAnimatorTo(float startY, float targetY, @Nullable final LagSession lagSession, final int targetSourceIndex, int fromSourceIndex) {
      // The line it scrolls to leads the chain; the rows behind are pulled along as it moves.
      if (this.lagChain != null) this.lagChain.setHead(targetSourceIndex, false);
      this.kickLagChain();
      float animationStartY = startY;
      boolean relayFromRunningAnimation = this.trackAnimator != null && this.trackAnimator.isRunning();
      float relayVelocityPxPerMs = relayFromRunningAnimation ? this.trackScrollVelocityPxPerMs : 0.0F;
      if (this.trackAnimator != null) {
         this.stopTrackAnimatorForVisualHandoff();
         animationStartY = this.trackContainer.getTranslationY();
      }

      if (Math.abs(animationStartY - targetY) < 0.5F) {
         this.trackContainer.setTranslationY(targetY);
         this.updateSourceAlphaTransitionProgress(1.0F);
         this.commitVisualSourceIndex(targetSourceIndex);
         long renderPositionMs = this.resolveRenderPositionMs();
         this.refreshRowAlphaState(renderPositionMs);
         this.refreshPrepCountdownRows(renderPositionMs);
         this.clearSourceAlphaTransition();
         this.resetTrackScrollMotionSample();
         this.kickLagChain();
      } else {
         this.activeLagSession = lagSession;
         float fromSourceTopAtStart = this.resolveSourceTopYInTrack(fromSourceIndex);
         float targetSourceTopAtStart = this.resolveSourceTopYInTrack(targetSourceIndex);
         final float handoffStartY = animationStartY;
         // A highlight handover begun while browsing carries on from where it is.
         final float alphaStartProgress = this.sourceAlphaTransitionActive ? this.clampAlpha(this.sourceAlphaTransitionProgress) : 0.0F;
         final ValueAnimator animator = com.lab.hv.RustValueAnimator.ofFloat(new float[]{handoffStartY, targetY});
         this.trackAnimator = animator;
         animator.setDuration(TRACK_SCROLL_ANIM_MS);
         float relayStartSlope = relayFromRunningAnimation
               ? TrackScrollRelayPolicy.resolveNormalizedStartSlope(
                     relayVelocityPxPerMs,
                     handoffStartY,
                     targetY,
                     TRACK_SCROLL_ANIM_MS
               )
               : 0.0F;
         if (relayStartSlope > 0.001F) {
            animator.setInterpolator(input ->
                  TrackScrollRelayPolicy.interpolate(input, relayStartSlope));
         } else {
            animator.setInterpolator(this.trackScrollInterpolator);
         }
         this.trackScrollLastSampleY = handoffStartY;
         this.trackScrollLastSampleUptimeMs = android.os.SystemClock.uptimeMillis();
         animator.addUpdateListener((a) -> {
            float baseTrackY = (Float)a.getAnimatedValue();
            float progress = a.getAnimatedFraction();
            float currentTrackY = this.resolveTrackTranslationYWithGeometryCompensation(baseTrackY, fromSourceIndex, fromSourceTopAtStart, targetSourceIndex, targetSourceTopAtStart, progress);
            this.trackContainer.setTranslationY(currentTrackY);
            this.updateTrackScrollMotionSample(currentTrackY);
            this.updateSourceAlphaTransitionProgress(alphaStartProgress + (1.0F - alphaStartProgress) * progress);
            long renderPositionMs = this.resolveRenderPositionMs();
            this.refreshRowAlphaState(renderPositionMs);
            this.refreshPrepCountdownRows(renderPositionMs);

         });
         animator.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
               boolean visualHandoffCancel = NowPlayingLyricsTickerView.this.visualHandoffCancelledTrackAnimator == animator;
               if (visualHandoffCancel) {
                  if (NowPlayingLyricsTickerView.this.trackAnimator == animator) {
                     NowPlayingLyricsTickerView.this.trackAnimator = null;
                  }

                  if (NowPlayingLyricsTickerView.this.visualHandoffCancelledTrackAnimator == animator) {
                     NowPlayingLyricsTickerView.this.visualHandoffCancelledTrackAnimator = null;
                  }

                  if (NowPlayingLyricsTickerView.this.activeLagSession != null && NowPlayingLyricsTickerView.this.activeLagSession == lagSession) {
                     NowPlayingLyricsTickerView.this.activeLagSession = null;
                  }

                  return;
               }

               boolean completed = NowPlayingLyricsTickerView.this.trackAnimator == animator;
               if (completed) {
                  NowPlayingLyricsTickerView.this.trackAnimator = null;
                  NowPlayingLyricsTickerView.this.resetTrackScrollMotionSample();
               }

               NowPlayingLyricsTickerView.this.updateSourceAlphaTransitionProgress(1.0F);
               if (completed) {
                  NowPlayingLyricsTickerView.this.commitVisualSourceIndex(targetSourceIndex);
                  NowPlayingLyricsTickerView.this.trackContainer.setTranslationY(NowPlayingLyricsTickerView.this.resolveTrackTranslationY(targetSourceIndex));
               }

               NowPlayingLyricsTickerView.this.clearSourceAlphaTransition();
               long renderPositionMs = NowPlayingLyricsTickerView.this.resolveRenderPositionMs();
               NowPlayingLyricsTickerView.this.refreshRowAlphaState(renderPositionMs);
               NowPlayingLyricsTickerView.this.refreshPrepCountdownRows(renderPositionMs);
               // The rows carry on settling by themselves.
               NowPlayingLyricsTickerView.this.kickLagChain();

            }

            public void onAnimationCancel(Animator animation) {
               boolean visualHandoffCancel = NowPlayingLyricsTickerView.this.visualHandoffCancelledTrackAnimator == animator;
               if (NowPlayingLyricsTickerView.this.trackAnimator == animator) {
                  NowPlayingLyricsTickerView.this.trackAnimator = null;
               }

               if (!visualHandoffCancel) {
                  NowPlayingLyricsTickerView.this.resetTrackScrollMotionSample();
                  NowPlayingLyricsTickerView.this.clearSourceAlphaTransition();
                  long renderPositionMs = NowPlayingLyricsTickerView.this.resolveRenderPositionMs();
                  NowPlayingLyricsTickerView.this.refreshRowAlphaState(renderPositionMs);
                  NowPlayingLyricsTickerView.this.refreshPrepCountdownRows(renderPositionMs);
               }

               if (NowPlayingLyricsTickerView.this.activeLagSession != null && NowPlayingLyricsTickerView.this.activeLagSession == lagSession) {
                  NowPlayingLyricsTickerView.this.activeLagSession = null;
               }

            }
         });
         animator.start();
      }
   }

   private void stopTrackAnimator() {
      this.stopTrackAnimator(false);
   }

   private void stopTrackAnimatorForVisualHandoff() {
      this.stopTrackAnimator(true);
   }

   private void stopTrackAnimator(boolean preserveVisualState) {
      if (this.trackAnimator != null) {
         ValueAnimator animator = this.trackAnimator;
         if (preserveVisualState) {
            this.visualHandoffCancelledTrackAnimator = animator;
         }

         try {
            animator.cancel();
         } catch (Throwable var2) {
            if (this.visualHandoffCancelledTrackAnimator == animator) {
               this.visualHandoffCancelledTrackAnimator = null;
            }
         }

         if (this.trackAnimator == animator) {
            this.trackAnimator = null;
         }

         if (this.visualHandoffCancelledTrackAnimator == animator) {
            this.visualHandoffCancelledTrackAnimator = null;
         }
      }
      if (!preserveVisualState) {
         this.resetTrackScrollMotionSample();
      }
   }

   private void updateTrackScrollMotionSample(float currentY) {
      long nowMs = android.os.SystemClock.uptimeMillis();
      if (!Float.isNaN(this.trackScrollLastSampleY)
            && this.trackScrollLastSampleUptimeMs != Long.MIN_VALUE) {
         long elapsedMs = nowMs - this.trackScrollLastSampleUptimeMs;
         if (elapsedMs > 0L && elapsedMs <= 100L) {
            float instantaneousVelocity = (currentY - this.trackScrollLastSampleY) / (float)elapsedMs;
            this.trackScrollVelocityPxPerMs = this.trackScrollVelocityPxPerMs * 0.25F
                  + instantaneousVelocity * 0.75F;
         }
      }
      this.trackScrollLastSampleY = currentY;
      this.trackScrollLastSampleUptimeMs = nowMs;
   }

   private void resetTrackScrollMotionSample() {
      this.trackScrollVelocityPxPerMs = 0.0F;
      this.trackScrollLastSampleY = Float.NaN;
      this.trackScrollLastSampleUptimeMs = Long.MIN_VALUE;
   }

   private void commitVisualSourceIndex(int sourceIndex) {
      int clampedSourceIndex = sourceIndex;
      if (sourceIndex < 0 || sourceIndex >= this.sourceGroups.size()) {
         clampedSourceIndex = -1;
      }

      if (this.visualSourceIndex != clampedSourceIndex) {
         this.visualSourceIndex = clampedSourceIndex;
         this.refreshRowColors();
      }
   }

   private int resolveSettledSourceIndex() {
      if (this.visualSourceIndex >= 0 && this.visualSourceIndex < this.sourceGroups.size()) {
         return this.visualSourceIndex;
      } else {
         return this.activeSourceIndex >= 0 && this.activeSourceIndex < this.sourceGroups.size() ? this.activeSourceIndex : -1;
      }
   }

   private void prewarmRowBlurMasks() {
      if (!(this.blurRadiusQuantizePx <= 0.01F) && !(this.rowBlurMaxPx <= 0.01F)) {
         int steps = Math.max(1, (int)Math.ceil((double)(this.rowBlurMaxPx / this.blurRadiusQuantizePx)));

         for(int i = 1; i <= steps; ++i) {
            SPText.prewarmBlurMaskRadiusPx(Math.min(this.rowBlurMaxPx, this.blurRadiusQuantizePx * (float)i));
         }

      }
   }

   // ---- the rows' lag: a chain of springs (LyricLagChain, rust/lyricchain) ----

   /** Every row back at its place at once, the chain still. */
   private void clearRowLagAnimations() {
      ++this.rowLagAnimToken;
      this.activeLagSession = null;
      if (this.lagChain == null || this.trackContainer == null) return;
      this.stopLagChain();
      this.lagChain.reset(this.trackContainer.getTranslationY());
      for (int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         if (holder.row.getTranslationY() != 0.0F) {
            holder.row.setTranslationY(0.0F);
            this.refreshRowEdgeFadeState(holder);
         }
      }
   }

   /** The rows settle back by themselves (the chain carries on from where it is). */
   private void releaseTrackedLagRowsToRest() {
      this.kickLagChain();
   }

   /**
    * A line change: the new line takes the lead and the chain is woken; the pull itself comes from
    * the track moving. No session object any more (kept in the signature for the callers).
    */
   @Nullable
   private LagSession buildDirectionalLagSession(int fromSourceIndex, int toSourceIndex, float trackStartY, float trackTargetY) {
      if (this.lagChain != null) this.lagChain.setHead(toSourceIndex, false);
      this.kickLagChain();
      return null;
   }

   /** Starts stepping the chain once per frame, just before drawing (so it sees the track's final place). */
   private void kickLagChain() {
      if (this.lagChain == null || !this.lagChain.isNative() || this.rowHolders.isEmpty()) return;
      this.syncLagChainRows();
      if (!this.lagChainRunning) {
         this.lagChainRunning = true;
         this.lagChainLastStepNs = 0L;
         this.getViewTreeObserver().addOnPreDrawListener(this.lagChainPreDraw);
      }
      this.invalidate();
   }

   private void stopLagChain() {
      if (this.lagChainRunning) {
         this.lagChainRunning = false;
         this.getViewTreeObserver().removeOnPreDrawListener(this.lagChainPreDraw);
      }
   }

   /** The rows' places and lines, as laid out now. */
   private void syncLagChainRows() {
      int count = this.rowHolders.size();
      if (this.lagChainTops.length < count) {
         this.lagChainTops = new float[count];
         this.lagChainSources = new int[count];
      }
      for (int i = 0; i < count; ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         this.lagChainTops[i] = (float)holder.top;
         this.lagChainSources[i] = holder.sourceIndex;
      }
      float[] tops = count == this.lagChainTops.length ? this.lagChainTops : java.util.Arrays.copyOf(this.lagChainTops, count);
      int[] sources = count == this.lagChainSources.length ? this.lagChainSources : java.util.Arrays.copyOf(this.lagChainSources, count);
      this.lagChain.setRows(tops, sources, count, (float)this.rowHeightPx);
   }

   /** One step of the chain, with the track where it will be drawn this frame. */
   private boolean stepLagChain() {
      if (!this.lagChainRunning) return true;
      if (this.manualScrollActive || this.progressScrubbingActive || this.rowHolders.isEmpty()) {
         // The user moves the lyrics: rows follow exactly.
         this.clearRowLagAnimations();
         return true;
      }
      long now = System.nanoTime();
      float dt = this.lagChainLastStepNs == 0L ? 1.0F / 120.0F : Math.min(0.05F, (float)(now - this.lagChainLastStepNs) / 1.0E9F);
      this.lagChainLastStepNs = now;
      this.syncLagChainRows();
      boolean resting = this.lagChain.step(dt, this.trackContainer.getTranslationY());
      for (int i = 0; i < this.rowHolders.size(); ++i) {
         RowHolder holder = (RowHolder)this.rowHolders.get(i);
         float offset = this.lagChain.offset(i);
         if (Math.abs(holder.row.getTranslationY() - offset) > 0.05F || (offset == 0.0F && holder.row.getTranslationY() != 0.0F)) {
            holder.row.setTranslationY(offset);
            this.refreshRowEdgeFadeState(holder);
         }
      }
      if (resting && (this.trackAnimator == null || !this.trackAnimator.isRunning())) {
         this.stopLagChain();
      } else {
         this.postInvalidateOnAnimation();
      }
      return true;
   }

   private float resolveTrackTranslationYWithGeometryCompensation(float baseTrackY, int fromSourceIndex, float fromSourceTopAtStart, int targetSourceIndex, float targetSourceTopAtStart, float progress) {
      float t = this.clampAlpha(progress);
      float fromShift = this.resolveSourceTopShiftY(fromSourceIndex, fromSourceTopAtStart);
      float targetShift = this.resolveSourceTopShiftY(targetSourceIndex, targetSourceTopAtStart);
      float compensationY = 0.0F;
      boolean hasFromShift = !Float.isNaN(fromShift);
      boolean hasTargetShift = !Float.isNaN(targetShift);
      if (hasFromShift && hasTargetShift) {
         compensationY = fromShift + (targetShift - fromShift) * t;
      } else if (hasTargetShift) {
         compensationY = targetShift * t;
      } else if (hasFromShift) {
         compensationY = fromShift * (1.0F - t);
      }

      return this.clampTrackTranslationY(baseTrackY + compensationY);
   }

   private float resolveSourceTopShiftY(int sourceIndex, float sourceTopAtStart) {
      return !Float.isNaN(sourceTopAtStart) && sourceIndex >= 0 && sourceIndex < this.sourceGroups.size() ? sourceTopAtStart - ((SourceGroup)this.sourceGroups.get(sourceIndex)).topYInTrack : Float.NaN;
   }

   private float resolveSourceTopYInTrack(int sourceIndex) {
      return sourceIndex >= 0 && sourceIndex < this.sourceGroups.size() ? ((SourceGroup)this.sourceGroups.get(sourceIndex)).topYInTrack : Float.NaN;
   }

   private float resolveTrackTranslationY(int sourceIndex) {
      if (sourceIndex < 0 && !this.timedLines.isEmpty() && !this.sourceGroups.isEmpty()) {
         // Before the first line is sung it already waits where the sung line sits. Resting the
         // track at the top instead left the first line far below (past the top spacer), so the
         // first line change was one long scroll with no pull, as no line was there to lag from.
         sourceIndex = 0;
      }
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      if (innerHeight <= 0) {
         return 0.0F;
      } else {
         float contentHeight = (float)Math.max(1, this.contentHeightPx);
         if (!this.sourceGroups.isEmpty() && sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
            float anchorY = this.resolveSourceAnchorY(innerHeight, sourceIndex);
            float target = anchorY - ((SourceGroup)this.sourceGroups.get(sourceIndex)).topYInTrack;
            if (contentHeight <= (float)innerHeight) {
               return ((float)innerHeight - contentHeight) * 0.5F;
            } else {
               float minY = this.resolveScrollableMinY(innerHeight, contentHeight);
               if (target < minY) {
                  target = minY;
               }

               float maxY = this.resolveScrollableMaxY(innerHeight);
               if (target > maxY) {
                  target = maxY;
               }

               return target;
            }
         } else {
            return contentHeight <= (float)innerHeight ? ((float)innerHeight - contentHeight) * 0.5F : 0.0F;
         }
      }
   }

   private float resolveActiveAnchorY(int innerHeight) {
      // Wide: four rows down, but never past the upper third of a short column (a phone turned
      // sideways), or the line being sung sits near the bottom with one line left below it.
      float anchor = this.wideAnchorMode
            ? Math.min((float)this.rowHeightPx * 4.0F, (float)innerHeight * this.wideAnchorShare)
            : (float)this.rowHeightPx * 1.35F;
      float min = (float)this.rowHeightPx * 0.5F;
      float max = Math.max(min, (float)innerHeight - (float)this.rowHeightPx * 0.5F);
      if (anchor < min) {
         anchor = min;
      }

      if (anchor > max) {
         anchor = max;
      }

      return anchor;
   }

   /**
    * Where a line's top rests while it is sung: near the top as {@link #resolveActiveAnchorY}, or,
    * centred, so that the whole line (wrapped rows and translation too) sits in the middle.
    */
   private float resolveSourceAnchorY(int innerHeight, int sourceIndex) {
      if (!this.centerAnchor) {
         return this.resolveActiveAnchorY(innerHeight);
      }
      float groupHeight = (float)this.rowHeightPx;
      if (sourceIndex >= 0 && sourceIndex < this.sourceGroups.size()) {
         SourceGroup group = (SourceGroup)this.sourceGroups.get(sourceIndex);
         if (group.lastRowIndex >= 0 && group.lastRowIndex < this.rowHolders.size()) {
            groupHeight = Math.max(1.0F, (float)((RowHolder)this.rowHolders.get(group.lastRowIndex)).bottom - group.topYInTrack);
         }
      }
      float min = (float)this.rowHeightPx * 0.5F;
      return Math.max(min, ((float)innerHeight - groupHeight) * 0.5F);
   }

   /**
    * How far down the lines may go: the content's head at the top, or, centred, far enough for
    * the first line to reach the middle (the space above it is shorter than half the view).
    */
   private float resolveScrollableMaxY(int innerHeight) {
      if (!this.centerAnchor || this.sourceGroups.isEmpty()) {
         return 0.0F;
      }
      SourceGroup first = (SourceGroup)this.sourceGroups.get(0);
      return Math.max(0.0F, this.resolveSourceAnchorY(innerHeight, 0) - first.topYInTrack);
   }

   /**
    * How far up the lines may go: past the content's foot, far enough for the last line to reach
    * the line-being-sung position. Before, the content stopped at the bottom of the view, so the
    * last lines never came up to it (they stayed low, in the edge fade) and could not be
    * scrolled to.
    */
   private float resolveScrollableMinY(int innerHeight, float contentHeight) {
      float minY = (float)innerHeight - contentHeight;
      if (!this.sourceGroups.isEmpty()) {
         SourceGroup last = (SourceGroup)this.sourceGroups.get(this.sourceGroups.size() - 1);
         minY = Math.min(minY, this.resolveSourceAnchorY(innerHeight, this.sourceGroups.size() - 1) - last.topYInTrack);
      }

      return minY;
   }

   private float clampTrackTranslationY(float value) {
      int innerHeight = this.getHeight() - this.getPaddingTop() - this.getPaddingBottom();
      float contentHeight = (float)Math.max(1, this.contentHeightPx);
      if (innerHeight <= 0) {
         return 0.0F;
      } else if (contentHeight <= (float)innerHeight) {
         return ((float)innerHeight - contentHeight) * 0.5F;
      } else {
         float minY = this.resolveScrollableMinY(innerHeight, contentHeight);
         float maxY = this.resolveScrollableMaxY(innerHeight);
         if (value < minY) {
            return minY;
         } else {
            return value > maxY ? maxY : value;
         }
      }
   }

   private long resolveRenderPositionMs() {
      return this.resolveRenderPositionMs(SystemClock.uptimeMillis());
   }

   private long resolveRenderPositionMs(long nowMs) {
      if (!this.tickerEnabled && !this.compactPlaybackClockEnabled) {
         return this.reportedPlaybackPositionMs;
      } else if (!this.playbackRunning) {
         return this.reportedPlaybackPositionMs;
      } else {
         long dt = Math.max(0L, nowMs - this.reportedUptimeMs);
         return this.reportedPlaybackPositionMs + Math.round((float)dt * this.reportedPlaybackSpeed);
      }
   }

   private static float sanitizePlaybackSpeed(float playbackSpeed) {
      if (Float.isNaN(playbackSpeed) || Float.isInfinite(playbackSpeed) || playbackSpeed <= 0.0F) {
         return 1.0F;
      }

      return Math.max(0.1F, Math.min(4.0F, playbackSpeed));
   }

   @NonNull
   private List<String> splitToFixedRows(@Nullable String text, int charsPerRow, float textSizePx) {
      if (TextUtils.isEmpty(text)) {
         return Collections.emptyList();
      } else {
         int safeRowChars = Math.max(1, charsPerRow);
         if (this.shouldUseWordAwareWrap(text)) {
            return this.splitWordAwareRows(text, safeRowChars, textSizePx);
         } else {
            float maxWidthPx = this.resolveRowAvailableWidthPx() - this.rowRightReservePx;
            return maxWidthPx <= this.dpF(40.0F) ? this.splitByCodePointBudget(text, safeRowChars) : this.splitCodePointByWidth(text, maxWidthPx, textSizePx, safeRowChars);
         }
      }
   }

   @NonNull
   private RowSourceMatch resolveRowSourceMatch(@NonNull String sourceText, @NonNull String rowText, int searchStartUtf16, int fallbackStartCodePoint) {
      int rowCodePointCount = rowText.codePointCount(0, rowText.length());
      int sourceCodePointCount = sourceText.codePointCount(0, sourceText.length());
      int safeFallbackStart = Math.max(0, Math.min(sourceCodePointCount, fallbackStartCodePoint));
      if (TextUtils.isEmpty(sourceText) || TextUtils.isEmpty(rowText)) {
         return new RowSourceMatch(safeFallbackStart, rowCodePointCount, this.offsetUtf16ByCodePointsSafely(sourceText, safeFallbackStart + rowCodePointCount));
      } else {
         int safeSearchStart = Math.max(0, Math.min(sourceText.length(), searchStartUtf16));
         int matchStartUtf16 = -1;
         int matchEndUtf16 = -1;

         for(int candidate = safeSearchStart; candidate < sourceText.length(); candidate += Character.charCount(sourceText.codePointAt(candidate))) {
            int end = this.resolveFlexibleRowTextEndUtf16(sourceText, rowText, candidate);
            if (end >= 0) {
               matchStartUtf16 = candidate;
               matchEndUtf16 = end;
               break;
            }
         }

         if (matchStartUtf16 < 0 && safeSearchStart > 0) {
            for(int candidate = 0; candidate < safeSearchStart && candidate < sourceText.length(); candidate += Character.charCount(sourceText.codePointAt(candidate))) {
               int end = this.resolveFlexibleRowTextEndUtf16(sourceText, rowText, candidate);
               if (end >= 0) {
                  matchStartUtf16 = candidate;
                  matchEndUtf16 = end;
                  break;
               }
            }
         }

         if (matchStartUtf16 >= 0 && matchEndUtf16 >= matchStartUtf16) {
            return new RowSourceMatch(sourceText.codePointCount(0, matchStartUtf16), rowCodePointCount, matchEndUtf16);
         } else {
            return new RowSourceMatch(safeFallbackStart, rowCodePointCount, this.offsetUtf16ByCodePointsSafely(sourceText, safeFallbackStart + rowCodePointCount));
         }
      }
   }

   private int resolveFlexibleRowTextEndUtf16(@NonNull String sourceText, @NonNull String rowText, int sourceStartUtf16) {
      int sourceIndex = sourceStartUtf16;
      int rowIndex = 0;

      while(rowIndex < rowText.length()) {
         int rowCp = rowText.codePointAt(rowIndex);
         if (this.isLineEdgeWhitespace(rowCp)) {
            if (sourceIndex >= sourceText.length()) {
               return -1;
            }

            int sourceCp = sourceText.codePointAt(sourceIndex);
            if (!this.isLineEdgeWhitespace(sourceCp)) {
               return -1;
            }

            do {
               sourceIndex += Character.charCount(sourceText.codePointAt(sourceIndex));
            } while(sourceIndex < sourceText.length() && this.isLineEdgeWhitespace(sourceText.codePointAt(sourceIndex)));

            do {
               rowIndex += Character.charCount(rowText.codePointAt(rowIndex));
            } while(rowIndex < rowText.length() && this.isLineEdgeWhitespace(rowText.codePointAt(rowIndex)));
         } else {
            if (sourceIndex >= sourceText.length()) {
               return -1;
            }

            int sourceCp = sourceText.codePointAt(sourceIndex);
            if (sourceCp != rowCp) {
               return -1;
            }

            sourceIndex += Character.charCount(sourceCp);
            rowIndex += Character.charCount(rowCp);
         }
      }

      return sourceIndex;
   }

   private int offsetUtf16ByCodePointsSafely(@NonNull String text, int targetCodePoint) {
      int safeTarget = Math.max(0, Math.min(text.codePointCount(0, text.length()), targetCodePoint));
      return text.offsetByCodePoints(0, safeTarget);
   }

   private boolean shouldUseWordAwareWrap(@Nullable String text) {
      if (TextUtils.isEmpty(text)) {
         return false;
      } else {
         boolean hasLatin = false;
         boolean hasSpace = false;
         int[] cps = text.codePoints().toArray();

         for(int i = 0; i < cps.length; ++i) {
            int cp = cps[i];
            if (Character.isWhitespace(cp)) {
               hasSpace = true;
            } else if (this.isAsciiLatinWordCodePoint(cp)) {
               hasLatin = true;
            }

            if (hasLatin && hasSpace) {
               return true;
            }
         }

         return false;
      }
   }

   private boolean isAsciiLatinWordCodePoint(int cp) {
      if (cp > 127) {
         return false;
      } else {
         return Character.isLetterOrDigit(cp) || cp == 39 || cp == 45;
      }
   }

   @NonNull
   private List<String> splitWordAwareRows(@NonNull String text, int charsPerRow, float textSizePx) {
      String collapsed = this.collapseSpaces(text);
      if (TextUtils.isEmpty(collapsed)) {
         return Collections.emptyList();
      } else {
         float maxWidthPx = this.resolveRowAvailableWidthPx() - this.rowRightReservePx;
         if (maxWidthPx <= this.dpF(40.0F)) {
            return this.splitByCodePointBudget(collapsed, Math.max(charsPerRow, 16));
         } else {
            String[] words = collapsed.split(" ");
            List<String> rows = new ArrayList<>();
            StringBuilder rowBuilder = new StringBuilder();
            float rowWidthPx = 0.0F;
            float spaceWidthPx = this.measureTextPx(" ", textSizePx);

            for(int i = 0; i < words.length; ++i) {
               String word = words[i];
               if (!TextUtils.isEmpty(word)) {
                  float wordWidthPx = this.measureTextPx(word, textSizePx);
                  if (rowBuilder.length() == 0) {
                     if (wordWidthPx <= maxWidthPx) {
                        rowBuilder.append(word);
                        rowWidthPx = wordWidthPx;
                     } else {
                        this.appendSplitLongWordByWidth(rows, word, maxWidthPx, textSizePx, charsPerRow);
                     }
                  } else {
                     float mergedWidthPx = rowWidthPx + spaceWidthPx + wordWidthPx;
                     if (mergedWidthPx <= maxWidthPx) {
                        rowBuilder.append(' ').append(word);
                        rowWidthPx = mergedWidthPx;
                     } else {
                        rows.add(rowBuilder.toString());
                        rowBuilder.setLength(0);
                        rowWidthPx = 0.0F;
                        if (wordWidthPx <= maxWidthPx) {
                           rowBuilder.append(word);
                           rowWidthPx = wordWidthPx;
                        } else {
                           this.appendSplitLongWordByWidth(rows, word, maxWidthPx, textSizePx, charsPerRow);
                        }
                     }
                  }
               }
            }

            if (rowBuilder.length() > 0) {
               rows.add(rowBuilder.toString());
            }

            return rows;
         }
      }
   }

   @NonNull
   private String collapseSpaces(@NonNull String text) {
      int[] cps = text.codePoints().toArray();
      StringBuilder out = new StringBuilder(text.length());
      boolean pendingSpace = false;

      for(int i = 0; i < cps.length; ++i) {
         int cp = cps[i];
         if (Character.isWhitespace(cp)) {
            if (out.length() > 0) {
               pendingSpace = true;
            }
         } else {
            if (pendingSpace) {
               out.append(' ');
               pendingSpace = false;
            }

            out.appendCodePoint(cp);
         }
      }

      return out.toString();
   }

   private void appendSplitLongWordByWidth(@NonNull List<String> rows, @NonNull String word, float maxWidthPx, float textSizePx, int fallbackCharsPerRow) {
      int[] cps = word.codePoints().toArray();
      if (cps.length != 0) {
         if (!(maxWidthPx <= this.dpF(40.0F))) {
            StringBuilder chunk = new StringBuilder();
            float chunkWidth = 0.0F;

            for(int i = 0; i < cps.length; ++i) {
               String unit = new String(cps, i, 1);
               float unitWidth = this.measureTextPx(unit, textSizePx);
               if (chunk.length() == 0) {
                  chunk.append(unit);
                  chunkWidth = unitWidth;
               } else if (chunkWidth + unitWidth <= maxWidthPx) {
                  chunk.append(unit);
                  chunkWidth += unitWidth;
               } else {
                  rows.add(chunk.toString());
                  chunk.setLength(0);
                  chunk.append(unit);
                  chunkWidth = unitWidth;
               }
            }

            if (chunk.length() > 0) {
               rows.add(chunk.toString());
            }

         } else {
            int step = Math.max(1, fallbackCharsPerRow);

            for(int i = 0; i < cps.length; i += step) {
               int end = Math.min(cps.length, i + step);
               rows.add(new String(cps, i, end - i));
            }

         }
      }
   }

   @NonNull
   private List<String> splitCodePointByWidth(@NonNull String text, float maxWidthPx, float textSizePx, int fallbackCharsPerRow) {
      int[] cps = text.codePoints().toArray();
      if (cps.length == 0) {
         return Collections.emptyList();
      } else if (maxWidthPx <= this.dpF(40.0F)) {
         return this.splitByCodePointBudget(text, fallbackCharsPerRow);
      } else {
         List<String> rows = new ArrayList<>();
         StringBuilder rowBuilder = new StringBuilder();
         float rowWidth = 0.0F;

         for(int i = 0; i < cps.length; ++i) {
            String unit = new String(cps, i, 1);
            float unitWidth = this.measureTextPx(unit, textSizePx);
            if (rowBuilder.length() == 0) {
               rowBuilder.append(unit);
               rowWidth = unitWidth;
            } else if (rowWidth + unitWidth <= maxWidthPx) {
               rowBuilder.append(unit);
               rowWidth += unitWidth;
            } else {
               rows.add(rowBuilder.toString());
               rowBuilder.setLength(0);
               rowBuilder.append(unit);
               rowWidth = unitWidth;
            }
         }

         if (rowBuilder.length() > 0) {
            rows.add(rowBuilder.toString());
         }

         return rows;
      }
   }

   @NonNull
   private List<String> splitByCodePointBudget(@NonNull String text, int budget) {
      int[] cps = text.codePoints().toArray();
      if (cps.length == 0) {
         return Collections.emptyList();
      } else {
         int safeBudget = Math.max(1, budget);
         List<String> rows = new ArrayList<>();

         for(int i = 0; i < cps.length; i += safeBudget) {
            int end = Math.min(cps.length, i + safeBudget);
            rows.add(new String(cps, i, end - i));
         }

         return rows;
      }
   }

   private float resolveRowAvailableWidthPx() {
      int widthPx = this.lastWrapWidthPx;
      if (widthPx <= 0) {
         widthPx = this.getWidth() - this.getPaddingLeft() - this.getPaddingRight();
      }

      if (widthPx <= 0) {
         widthPx = Math.round((float)this.getResources().getDisplayMetrics().widthPixels * 0.76F);
      }

      return (float)Math.max(1, widthPx);
   }

   private float measureTextPx(@NonNull String text, float textSizePx) {
      this.wrapMeasurePaint.setTextSize(textSizePx);
      return this.wrapMeasurePaint.measureText(text);
   }

   private long parseTimestampMs(@Nullable String hourPart, @Nullable String minutePart, @Nullable String secondPart, @Nullable String fracPart) {
      if (!TextUtils.isEmpty(minutePart) && !TextUtils.isEmpty(secondPart)) {
         try {
            int hours = 0;
            if (!TextUtils.isEmpty(hourPart)) {
               hours = Integer.parseInt(hourPart);
            }

            int minutes = Integer.parseInt(minutePart);
            int seconds = Integer.parseInt(secondPart);
            int millis = 0;
            if (!TextUtils.isEmpty(fracPart)) {
               String frac = fracPart.trim();
               if (frac.length() == 1) {
                  millis = Integer.parseInt(frac) * 100;
               } else if (frac.length() == 2) {
                  millis = Integer.parseInt(frac) * 10;
               } else {
                  millis = Integer.parseInt(frac.substring(0, 3));
               }
            }

            return hours >= 0 && minutes >= 0 && seconds >= 0 && seconds < 60 ? (long)hours * 3600000L + (long)minutes * 60000L + (long)seconds * 1000L + (long)millis : -1L;
         } catch (Throwable var10) {
            return -1L;
         }
      } else {
         return -1L;
      }
   }

   private float dpF(float value) {
      return TypedValue.applyDimension(1, value, this.getResources().getDisplayMetrics());
   }

   private float sp(float value) {
      return TypedValue.applyDimension(2, value, this.getResources().getDisplayMetrics());
   }

   private boolean shouldDisableBlurForContent(@Nullable String source, @NonNull ParsedLyricsData parsed) {
      if (!parsed.timedLines.isEmpty()) {
         return false;
      } else {
         return parsed.plainLines.isEmpty() ? true : this.isStatusOnlyLyricsContent(source, parsed);
      }
   }

   private boolean shouldAllowManualScrollForParsedContent(@Nullable String source, @NonNull ParsedLyricsData parsed) {
      if (!parsed.timedLines.isEmpty()) {
         return true;
      } else if (parsed.plainLines.isEmpty()) {
         return false;
      } else {
         return !this.isStatusOnlyLyricsContent(source, parsed);
      }
   }

   private boolean isStatusOnlyLyricsContent(@Nullable String source, @NonNull ParsedLyricsData parsed) {
      String candidate = !TextUtils.isEmpty(source) ? source : this.joinPlainLines(parsed.plainLines);
      if (TextUtils.isEmpty(candidate)) {
         return true;
      } else {
         String normalized = candidate.trim();
         if (TextUtils.isEmpty(normalized)) {
            return true;
         } else {
            String compact = normalized.replace('…', '.').replace('：', ':').replace(" ", "").toLowerCase(Locale.ROOT);
            return compact.contains("暂无歌词") || compact.contains("无歌词") || compact.contains("未找到歌词") || compact.contains("无法获取") || compact.contains("无法获取歌词") || compact.contains("获取失败") || compact.contains("无法加载歌词") || compact.contains("歌词获取失败") || compact.contains("正在获取") || compact.contains("正在获取歌词") || compact.contains("正在读取歌词") || compact.contains("正在读取标签歌词") || compact.contains("正在加载歌词") || compact.contains("正在加载") || compact.contains("加载歌词") || compact.contains("nolyrics") || compact.contains("lyricsunavailable") || compact.contains("loadinglyrics") || compact.contains("fetchinglyrics") || compact.contains("unabletofetchlyrics") || compact.contains("unabletoloadlyrics") || compact.equals("loading") || compact.equals("fetching");
         }
      }
   }

   @NonNull
   private String joinPlainLines(@NonNull List<String> lines) {
      if (lines.isEmpty()) {
         return "";
      } else {
         StringBuilder builder = new StringBuilder();

         for(int i = 0; i < lines.size(); ++i) {
            String line = (String)lines.get(i);
            if (!TextUtils.isEmpty(line)) {
               if (builder.length() > 0) {
                  builder.append('\n');
               }

               builder.append(line);
            }
         }

         return builder.toString();
      }
   }

   private boolean shouldHandleLyricsTouch() {
      return this.isEnabled() && this.tickerEnabled && this.manualScrollAllowedForCurrentContent && !this.rowHolders.isEmpty() && this.getWidth() > 0 && this.getHeight() > 0;
   }

   static {
	      NON_LYRIC_HEAD_KEYWORDS = new String[]{AppStrings.get(string.salam_text_0308_df99cc), AppStrings.get(string.salam_text_0309_fc027b), AppStrings.get(string.salam_text_0310_2c7a29), "lyrics", "lyricist", AppStrings.get(string.salam_text_0311_0a82dc), AppStrings.get(string.salam_text_0312_779e67), "composer", "compose", AppStrings.get(string.salam_text_0313_90ea0c), "arranger", "arrangement", "调教", "民乐配器", AppStrings.get(string.salam_text_0298_b1c7f3), AppStrings.get(string.salam_text_0314_3c0adc), "vocal", "vocals", AppStrings.get(string.salam_text_0315_7d3944), AppStrings.get(string.salam_text_0316_2ef641), "producer", "prod", AppStrings.get(string.salam_text_0317_ccdf37), "executiveproducer", AppStrings.get(string.salam_text_0318_a04e9e), "mix", "mixing", AppStrings.get(string.salam_text_0319_104cf2), "master", "mastering", AppStrings.get(string.salam_text_0320_3ba60a), "recording", "engineer", AppStrings.get(string.salam_text_0321_047643), AppStrings.get(string.salam_text_0322_47f586), "chorus", "harmony", AppStrings.get(string.salam_text_0323_b29f73), "vocalproducer", AppStrings.get(string.salam_text_0324_3ceb46), "guitar", AppStrings.get(string.salam_text_0325_41b2e9), "bass", AppStrings.get(string.salam_text_0326_11edfb), "drum", "drums", AppStrings.get(string.salam_text_0327_e2766f), "keyboard", AppStrings.get(string.salam_text_0328_c35530), "piano", AppStrings.get(string.salam_text_0329_1011c4), "violin", "strings", "古筝", "尺八", AppStrings.get(string.salam_text_0330_707895), AppStrings.get(string.salam_text_0331_dbe657), AppStrings.get(string.salam_text_0332_a37cdc), "band", "orchestra", "Presented by", "Mixedandmasteredby", AppStrings.get(string.salam_text_0333_398e83), AppStrings.get(string.salam_text_0334_687c35), AppStrings.get(string.salam_text_0335_b465d4), AppStrings.get(string.salam_text_0336_fa4e33)};
      NON_LYRIC_PHRASE_KEYWORDS = new String[]{"instrumental", "intro", "interlude", "outro", AppStrings.get(string.salam_text_0337_9c1f7e), AppStrings.get(string.salam_text_0338_723314), AppStrings.get(string.salam_text_0339_09a0cf), AppStrings.get(string.salam_text_0340_cffecb), AppStrings.get(string.salam_text_0341_4daf15), AppStrings.get(string.salam_text_0342_bca467)};
   }

   private static final class TimedLine {
      final long timeMs;
      @NonNull
      final String text;
      final boolean prepCountdown;
      final long prepTargetTimeMs;
      @Nullable
      final KaraokeTimeline karaokeTimeline;

      TimedLine(long timeMs, @NonNull String text) {
         this(timeMs, text, false, -1L, (KaraokeTimeline)null);
      }

      TimedLine(long timeMs, @NonNull String text, boolean prepCountdown, long prepTargetTimeMs) {
         this(timeMs, text, prepCountdown, prepTargetTimeMs, (KaraokeTimeline)null);
      }

      TimedLine(long timeMs, @NonNull String text, boolean prepCountdown, long prepTargetTimeMs, @Nullable KaraokeTimeline karaokeTimeline) {
         this.timeMs = timeMs;
         this.text = text;
         this.prepCountdown = prepCountdown;
         this.prepTargetTimeMs = prepTargetTimeMs;
         this.karaokeTimeline = karaokeTimeline;
      }
   }

   private static final class KaraokeTimeline {
      @NonNull
      final long[] tokenTimeMs;
      @NonNull
      final int[] tokenStartCodePoint;
      @NonNull
      final int[] tokenEndCodePoint;
      @NonNull
      final int[] tokenVisibleCodePointCount;
      @NonNull
      final int[] codePointVisibleOrdinalInToken;
      final long averageGlyphIntervalMs;
      /**
       * Where the last word ends when the lyrics say so (a held last note); -1 when they do not.
       * Kept before the next line's start once the lines are known.
       */
      long terminalEndMs = -1L;

      KaraokeTimeline(@NonNull long[] tokenTimeMs, @NonNull int[] tokenStartCodePoint, @NonNull int[] tokenEndCodePoint, @NonNull int[] tokenVisibleCodePointCount, @NonNull int[] codePointVisibleOrdinalInToken, long averageGlyphIntervalMs) {
         this.tokenTimeMs = tokenTimeMs;
         this.tokenStartCodePoint = tokenStartCodePoint;
         this.tokenEndCodePoint = tokenEndCodePoint;
         this.tokenVisibleCodePointCount = tokenVisibleCodePointCount;
         this.codePointVisibleOrdinalInToken = codePointVisibleOrdinalInToken;
         this.averageGlyphIntervalMs = Math.max(1L, averageGlyphIntervalMs);
      }

      int resolveVisibleOrdinalInToken(int codePointIndex) {
         return codePointIndex >= 0 && codePointIndex < this.codePointVisibleOrdinalInToken.length ? this.codePointVisibleOrdinalInToken[codePointIndex] : -1;
      }
   }

   private static final class TimedTextToken {
      final long timeMs;
      final int order;
      @NonNull
      final String text;
      final boolean nonLyric;
      @Nullable
      final KaraokeTimeline karaokeTimeline;
      @Nullable
      final String performerMarker;

      TimedTextToken(long timeMs,
                     int order,
                     @NonNull String text,
                     boolean nonLyric,
                     @Nullable KaraokeTimeline karaokeTimeline,
                     @Nullable String performerMarker) {
         this.timeMs = timeMs;
         this.order = order;
         this.text = text;
         this.nonLyric = nonLyric;
         this.karaokeTimeline = karaokeTimeline;
         this.performerMarker = performerMarker;
      }
   }

   private static final class SourceGroup {
      final long timeMs;
      final int sourceIndex;
      final int firstMainRowIndex;
      final int mainRowCount;
      final int firstTranslationRowIndex;
      final int translationRowCount;
      final int firstRowIndex;
      final int lastRowIndex;
      float topYInTrack;
      final boolean prepCountdown;
      final long prepTargetTimeMs;
      float prepRevealVisualProgress = Float.NaN;
      float geometryControlScale = Float.NaN;
      float geometryPrepReveal = Float.NaN;
      float presentationScale = Float.NaN;
      long geometryTranslationVersion = Long.MIN_VALUE;
      int geometryTopPx, geometryHeightPx, geometrySentenceGapPx;

      SourceGroup(long timeMs, int sourceIndex, int firstMainRowIndex, int mainRowCount, int firstTranslationRowIndex, int translationRowCount, int firstRowIndex, int lastRowIndex, float topYInTrack, boolean prepCountdown, long prepTargetTimeMs) {
         this.timeMs = timeMs;
         this.sourceIndex = sourceIndex;
         this.firstMainRowIndex = firstMainRowIndex;
         this.mainRowCount = mainRowCount;
         this.firstTranslationRowIndex = firstTranslationRowIndex;
         this.translationRowCount = translationRowCount;
         this.firstRowIndex = firstRowIndex;
         this.lastRowIndex = lastRowIndex;
         this.topYInTrack = topYInTrack;
         this.prepCountdown = prepCountdown;
         this.prepTargetTimeMs = prepTargetTimeMs;
      }
   }

   private static final class CollapsedKaraokeSpan {
      final int anchorIndex;
      final int lastSkippedIndex;
      final int targetIndex;
      final long scrollStartMs;
      final long targetTimeMs;
      final long endTimeMs;

      CollapsedKaraokeSpan(int anchorIndex, int lastSkippedIndex, int targetIndex, long scrollStartMs, long targetTimeMs, long endTimeMs) {
         this.anchorIndex = anchorIndex;
         this.lastSkippedIndex = lastSkippedIndex;
         this.targetIndex = targetIndex;
         this.scrollStartMs = scrollStartMs;
         this.targetTimeMs = targetTimeMs;
         this.endTimeMs = endTimeMs;
      }
   }

   private static final class RowHolder {
      final int sourceIndex;
      int top;
      int bottom;
      float baseAlphaWithoutEdgeFade = 1.0F;
      /** Skipped by refreshRowAlphaState while away from the viewport. */
      boolean baseStateStale;
      final boolean translation;
      final boolean prepCountdown;
      final long prepTargetTimeMs;
      final int lineCodePointStart;
      final int lineCodePointCount;
      @NonNull
      final CharRowFrame row;
      int lagAnimationToken;
      int lagReleaseOwnerToken = Integer.MIN_VALUE;
      int lagHandoffOwnerToken = Integer.MIN_VALUE;
      float lagHandoffStartY;
      boolean lagReleaseRunning;
      @Nullable
      ValueAnimator lagReleaseAnimator;

      RowHolder(int sourceIndex, int top, int bottom, boolean translation, boolean prepCountdown, long prepTargetTimeMs, int lineCodePointStart, int lineCodePointCount, @NonNull CharRowFrame row) {
         this.sourceIndex = sourceIndex;
         this.top = top;
         this.bottom = bottom;
         this.translation = translation;
         this.prepCountdown = prepCountdown;
         this.prepTargetTimeMs = prepTargetTimeMs;
         this.lineCodePointStart = lineCodePointStart;
         this.lineCodePointCount = lineCodePointCount;
         this.row = row;
      }
   }

   private static final class RowSourceMatch {
      final int startCodePoint;
      final int codePointCount;
      final int nextSearchUtf16;

      RowSourceMatch(int startCodePoint, int codePointCount, int nextSearchUtf16) {
         this.startCodePoint = startCodePoint;
         this.codePointCount = codePointCount;
         this.nextSearchUtf16 = nextSearchUtf16;
      }
   }

   private static final class ParsedLyricsData {
      @NonNull
      final List<TimedLine> timedLines;
      @NonNull
      final List<TimedLine> timedNonLyricLines;
      @NonNull
      final List<String> timedTranslations;
      @NonNull
      final List<KaraokeTimeline> timedTranslationTimelines;
      @NonNull
      final List<String> plainLines;
      @NonNull
      final List<String> plainTranslations;

      ParsedLyricsData(@NonNull List<TimedLine> timedLines,
                       @NonNull List<TimedLine> timedNonLyricLines,
                       @NonNull List<String> timedTranslations,
                       @NonNull List<KaraokeTimeline> timedTranslationTimelines,
                       @NonNull List<String> plainLines,
                       @NonNull List<String> plainTranslations) {
         this.timedLines = timedLines;
         this.timedNonLyricLines = timedNonLyricLines;
         this.timedTranslations = timedTranslations;
         this.timedTranslationTimelines = timedTranslationTimelines;
         this.plainLines = plainLines;
         this.plainTranslations = plainTranslations;
      }
   }

   private static final class LagSession {
      final int token;
      final int direction;
      final int targetSourceIndex;
      @NonNull
      final List<RowHolder> affectedRows;
      final float baseLag;
      float lastLagProgress;
      boolean releaseStarted;

      LagSession(int token, int direction, int targetSourceIndex, float baseLag) {
         this.token = token;
         this.direction = direction;
         this.targetSourceIndex = targetSourceIndex;
         this.affectedRows = new ArrayList<>();
         this.baseLag = baseLag;
         this.lastLagProgress = 0.0F;
         this.releaseStarted = false;
      }

      void replaceAffectedRows(@NonNull List<RowHolder> rows) {
         this.affectedRows.clear();
         this.affectedRows.addAll(rows);
      }
   }

   /**
    * Holds the lyric rows and the pressed-row card. The ticker measures and places every child
    * itself (layoutDynamicRowsIfPossible, layoutPressedRowCardForSource). A plain FrameLayout
    * pass first laid each row out at its LayoutParams size and the ticker then moved it back, so
    * every track height change (each line switch, translation toggle and prep reveal frame)
    * resized, re-recorded and re-blurred all rows twice.
    */
   private static final class RowTrackLayout extends FrameLayout {
      RowTrackLayout(@NonNull Context context) {
         super(context);
      }

      @Override
      protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
         this.setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
      }

      @Override
      protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      }
   }

   private static final class PressedRowCardView extends View {
      private final Paint fillPaint = com.lab.hv.util.AppFont.track(new Paint(1));
      private final float cornerRadiusPx;

      PressedRowCardView(@NonNull Context context, float cornerRadiusPx) {
         super(context);
         this.cornerRadiusPx = Math.max(0.0F, cornerRadiusPx);
         this.setWillNotDraw(false);
      }

      void setCardColor(int color) {
         if (this.fillPaint.getColor() != color) {
            this.fillPaint.setColor(color);
            this.invalidate();
         }
      }

      protected void onDraw(@NonNull Canvas canvas) {
         float width = (float)this.getWidth();
         float height = (float)this.getHeight();
         if (!(width <= 0.0F) && !(height <= 0.0F)) {
            if ((this.fillPaint.getColor() >>> 24 & 255) != 0) {
               canvas.drawRoundRect(0.0F, 0.0F, width, height, this.cornerRadiusPx, this.cornerRadiusPx, this.fillPaint);
            }
         }
      }
   }

   private static final class CharRowFrame extends FrameLayout {
      private static final Object ROW_BLUR_EFFECT_CACHE_LOCK = new Object();
      private static final SparseArray<RenderEffect> ROW_BLUR_EFFECT_CACHE = new SparseArray<>();
      private final float charTextPx;
      private final float weightStrengthPx;
      private final float prepDotWeightStrengthPx;
      private final float glyphEdgePaddingPx;
      private final float prepDotRestBlurPx;
      private final float blurRadiusQuantizePx;
      private final float karaokeScanFeatherPx;
      private final TextPaint glyphMeasurePaint;
      private final SPText rowTextView;
      /**
       * The row's glyph runs. They are drawn by the row itself (see {@link LyricGlyphRun}), not
       * as child views: one display list per row instead of a RenderNode, measure, layout and
       * record per glyph, with exactly what the per-glyph views drew.
       */
      private final ArrayList<LyricGlyphRun> charViews = new ArrayList<>();
      private final LyricGlyphRun.Paints glyphPaints = new LyricGlyphRun.Paints();
      /** Runs placed by the last child layout; any past the row's right edge are not drawn. */
      private int laidOutGlyphCount = 0;
      @NonNull
      private String currentRowText = "";
      @NonNull
      private int[] rowCodePoints = new int[0];
      @NonNull
      private int[] codePointTextStart = new int[0];
      @NonNull
      private float[] codePointLeftPx = new float[0];
      @NonNull
      private float[] codePointAdvancePx = new float[0];
      @NonNull
      private int[] glyphCodePointStart = new int[0];
      @NonNull
      private int[] glyphCodePointCount = new int[0];
      @NonNull
      private long[] glyphLiftStartTimeMs = new long[0];
      @NonNull
      private long[] glyphLiftNaturalDurationMs = new long[0];
      @NonNull
      private long[] glyphLiftNextStartGapMs = new long[0];
      /** When each code point of the line (local) starts to rise; Long.MAX_VALUE: never timed. */
      private long[] codePointLiftStartMs = new long[0];
      /** How long after its run's start the run's last letter starts (letter-by-letter rise). */
      private long[] glyphLiftLetterSpreadMs = new long[0];
      private float[] letterLiftScratchPx = new float[8];
      private int visibleCharCount = 0;
      private int visibleGlyphCount = 0;
      private boolean prepDotsMode = false;
      private boolean karaokeMode = false;
      private boolean usingGlyphViews = false;
      private boolean glyphViewsPrimed = false;
      private float currentBlurRadiusPx = 0.0F;
      private int currentSolidColor = -15066598;
      private boolean currentUseHdrColor = false;
      private long currentHdrColor = 0L;
      @Nullable
      private KaraokeTimeline lastKaraokeTimeline = null;
      private int lastKaraokeLineStart = Integer.MIN_VALUE;
      private int lastKaraokeLineCount = Integer.MIN_VALUE;
      private long lastKaraokePositionMs = Long.MIN_VALUE;
      private long lastKaraokeLiftPositionMs = Long.MIN_VALUE;
      private int lastKaraokeRevealColor = 0;
      private int lastKaraokePendingColor = 0;
      private boolean lastKaraokeUseHdrHighlight = false;
      private long lastKaraokeRevealHdrColor = 0L;
      private long lastKaraokePendingHdrColor = 0L;
      private float lastKaraokeBlurRadiusPx = -1.0F;
      @Nullable
      private KaraokeTimeline glyphRunKaraokeTimeline = null;
      private int glyphRunLineStart = Integer.MIN_VALUE;
      private int glyphRunLineCount = Integer.MIN_VALUE;
      private float contentPresentationScale = 1.0F;
      private float karaokeGlyphRestOffsetPx = 0.0F;
      private float karaokeGlyphLongNoteExtraLiftPx = 0.0F;
      private float karaokeGlyphLongNoteBouncePx = 0.0F;
      private float karaokeGlyphLongNoteGlowInnerRadiusPx = 0.0F;
      private float karaokeGlyphLongNoteGlowOuterRadiusPx = 0.0F;
      private float pressedFeedbackProgress = 0.0F;
      private float pressedContentSinkPx = 0.0F;
      private float blurBoundsOverflowPx = 0.0F;
      private float appliedGlyphSoftwareBlurRadiusPx = Float.NaN;
      private float appliedRowRenderBlurRadiusPx = Float.NaN;
      @Nullable
      private RenderEffect rowBlurEffect;
      @Nullable
      private RenderNode rowBlurNode;
      private boolean childLayoutDirty = true;
      private int lastChildLayoutWidth = -1;
      private int lastChildLayoutHeight = -1;
      private boolean lastChildLayoutGlyphMode = false;
      private float lastChildLayoutScale = Float.NaN;
      private float lastChildLayoutPressedProgress = Float.NaN;
      private float lastChildLayoutOverflowPx = Float.NaN;
      private boolean prepDotsProgressActive = false;
      private float lastPrepDotsFinalStageProgress = -1.0F;
      private float lastPrepDotsPhaseBase = 0.0F;

      CharRowFrame(@NonNull Context context, float charTextPx, float weightStrengthPx, @Nullable android.graphics.Typeface typeface, float glyphEdgePaddingPx, float prepDotRestBlurPx, float blurRadiusQuantizePx) {
         super(context);
         this.charTextPx = charTextPx;
         this.weightStrengthPx = weightStrengthPx;
         this.prepDotWeightStrengthPx = Math.max(0.0F, weightStrengthPx * 0.25F);
         this.glyphEdgePaddingPx = glyphEdgePaddingPx;
         this.prepDotRestBlurPx = prepDotRestBlurPx;
         this.blurRadiusQuantizePx = blurRadiusQuantizePx;
         this.karaokeScanFeatherPx = Math.max(1.0F, charTextPx * 0.82F);
         this.glyphMeasurePaint = com.lab.hv.util.AppFont.track(new TextPaint(1));
         this.glyphMeasurePaint.setTextSize(charTextPx);
         this.glyphMeasurePaint.setTypeface(typeface);
         this.glyphPaints.setTypeface(typeface);
         this.setClipChildren(false);
         this.setClipToPadding(false);
         this.rowTextView = new SPText(context);
         this.rowTextView.setTextSizePx(charTextPx);
         this.rowTextView.setWeightStrengthPx(weightStrengthPx);
         this.rowTextView.setFontTypeface(typeface);
         this.rowTextView.setEdgePaddingPx(glyphEdgePaddingPx);
         this.rowTextView.setVisibility(8);
         this.addView(this.rowTextView, new FrameLayout.LayoutParams(-2, -2));
      }

      @Override
      public boolean hasOverlappingRendering() {
         return false;
      }

      @Override
      protected void dispatchDraw(@NonNull Canvas canvas) {
         RenderEffect blur = this.rowBlurEffect;
         if (blur != null && VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            // The row's content goes through a node carrying the blur; the row itself is a layer
            // while blurred (see applyRowRenderEffectBlur), so the blurred result is kept.
            RenderNode node = this.rowBlurNode;
            if (node == null) {
               node = new RenderNode("LyricRowBlur");
               this.rowBlurNode = node;
            }
            int width = this.getWidth();
            int height = this.getHeight();
            node.setPosition(0, 0, width, height);
            node.setRenderEffect(blur);
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
               this.drawRowContent(recording);
            } finally {
               node.endRecording();
            }
            canvas.drawRenderNode(node);
            return;
         }
         this.drawRowContent(canvas);
      }

      private void drawRowContent(@NonNull Canvas canvas) {
         super.dispatchDraw(canvas);
         if (this.usingGlyphViews) {
            // In child order, as the glyph views were drawn: each run's glow under its own text.
            int count = Math.min(this.laidOutGlyphCount, Math.min(this.visibleGlyphCount, this.charViews.size()));
            for(int i = 0; i < count; ++i) {
               ((LyricGlyphRun)this.charViews.get(i)).draw(canvas);
            }
         }
      }

      // The ticker positions and fades rows through setTranslationY/setAlpha (lag, edge fade,
      // source alpha). The entrance cascade adds its own offset and fade on top here, so neither
      // overwrites the other; the getters keep returning the ticker's own values.
      private float layoutTranslationY;
      private float layoutAlpha = 1.0F;
      private float presentationOffsetY;
      private float presentationAlpha = 1.0F;
      // View.setTranslationY skips the write when the value equals getTranslationY(); while it
      // runs, the getter must report what the RenderNode really holds.
      private boolean applyingRenderTranslation;

      @Override
      public void setTranslationY(float translationY) {
         this.layoutTranslationY = translationY;
         this.applyRenderTranslation(translationY + this.presentationOffsetY);
      }

      @Override
      public float getTranslationY() {
         return this.applyingRenderTranslation ? super.getTranslationY() : this.layoutTranslationY;
      }

      private void applyRenderTranslation(float value) {
         this.applyingRenderTranslation = true;
         try {
            super.setTranslationY(value);
         } finally {
            this.applyingRenderTranslation = false;
         }
      }

      @Override
      public void setAlpha(float alpha) {
         this.layoutAlpha = alpha;
         super.setAlpha(alpha * this.presentationAlpha);
      }

      @Override
      public float getAlpha() {
         return this.layoutAlpha;
      }

      void setPresentationTransform(float offsetY, float alpha) {
         if (Math.abs(this.presentationOffsetY - offsetY) < 0.01F && Math.abs(this.presentationAlpha - alpha) < 0.001F) {
            return;
         }

         this.presentationOffsetY = offsetY;
         this.presentationAlpha = alpha;
         this.applyRenderTranslation(this.layoutTranslationY + offsetY);
         super.setAlpha(this.layoutAlpha * alpha);
      }

      void setPrepDotsMode(boolean enabled) {
         if (this.prepDotsMode != enabled) {
            this.prepDotsMode = enabled;
            this.clearPrepDotsProgressState();
            this.applyGlyphWeightMode();
            this.rowTextView.setWeightStrengthPx(this.prepDotsMode ? this.prepDotWeightStrengthPx : this.weightStrengthPx);
            if (this.prepDotsMode) {
               this.primeGlyphViewsIfNeeded();
               this.ensureGlyphViewsForCurrentRow();
            } else {
               this.resetGlyphTransforms();
               if (!this.usingGlyphViews && this.visibleCharCount > 0) {
                  this.ensureGlyphViewsForCurrentRow();
               }
            }

            this.syncUniformBlurState();
         }
      }

      void setRowText(@NonNull String rowText) {
         this.currentRowText = rowText;
         this.clearPrepDotsProgressState();
         this.rowTextView.setTextContent(rowText);
         if (!this.prepDotsMode) {
            this.resetGlyphTransforms();
         }

         this.karaokeMode = false;
         this.usingGlyphViews = false;
         this.glyphViewsPrimed = false;
         this.rowCodePoints = rowText.codePoints().toArray();
         this.visibleCharCount = this.rowCodePoints.length;
         this.visibleGlyphCount = 0;
         this.glyphRunKaraokeTimeline = null;
         this.glyphRunLineStart = Integer.MIN_VALUE;
         this.glyphRunLineCount = Integer.MIN_VALUE;
         this.rebuildCodePointMetrics();
         this.markChildLayoutDirty();
         this.lastKaraokeTimeline = null;
         this.lastKaraokeLineStart = Integer.MIN_VALUE;
         this.lastKaraokeLineCount = Integer.MIN_VALUE;
         this.lastKaraokePositionMs = Long.MIN_VALUE;
         this.lastKaraokeLiftPositionMs = Long.MIN_VALUE;
         this.lastKaraokeUseHdrHighlight = false;
         this.primeGlyphViewsIfNeeded();
         this.ensureGlyphViewsForCurrentRow();
         this.applyPendingKaraokeGlyphLift();
         this.syncUniformBlurState();
      }

      void setKaraokeGlyphPresentationPx(float restOffsetPx,
                                         float longNoteExtraLiftPx,
                                         float longNoteBouncePx,
                                         float longNoteGlowInnerRadiusPx,
                                         float longNoteGlowOuterRadiusPx) {
         float safeRestOffsetPx = Math.max(0.0F, restOffsetPx);
         float safeExtraLiftPx = Math.max(0.0F, longNoteExtraLiftPx);
         float safeBouncePx = Math.max(0.0F, longNoteBouncePx);
         float safeGlowInnerRadiusPx = Math.max(0.0F, longNoteGlowInnerRadiusPx);
         float safeGlowOuterRadiusPx = Math.max(safeGlowInnerRadiusPx, longNoteGlowOuterRadiusPx);
         if (!(Math.abs(this.karaokeGlyphRestOffsetPx - safeRestOffsetPx) < 0.001F)
               || !(Math.abs(this.karaokeGlyphLongNoteExtraLiftPx - safeExtraLiftPx) < 0.001F)
               || !(Math.abs(this.karaokeGlyphLongNoteBouncePx - safeBouncePx) < 0.001F)
               || !(Math.abs(this.karaokeGlyphLongNoteGlowInnerRadiusPx - safeGlowInnerRadiusPx) < 0.001F)
               || !(Math.abs(this.karaokeGlyphLongNoteGlowOuterRadiusPx - safeGlowOuterRadiusPx) < 0.001F)) {
            this.karaokeGlyphRestOffsetPx = safeRestOffsetPx;
            this.karaokeGlyphLongNoteExtraLiftPx = safeExtraLiftPx;
            this.karaokeGlyphLongNoteBouncePx = safeBouncePx;
            this.karaokeGlyphLongNoteGlowInnerRadiusPx = safeGlowInnerRadiusPx;
            this.karaokeGlyphLongNoteGlowOuterRadiusPx = safeGlowOuterRadiusPx;
            this.lastKaraokePositionMs = Long.MIN_VALUE;
            this.lastKaraokeLiftPositionMs = Long.MIN_VALUE;
            this.applyPendingKaraokeGlyphLift();
            this.invalidate();
         }
      }

      private void applyPendingKaraokeGlyphLift() {
         this.rowTextView.setTranslationY(0.0F);
         this.rowTextView.setPresentationTranslationYPx(0.0F);
         for(int i = 0; i < this.charViews.size(); ++i) {
            LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
            glyph.setTranslationY(0.0F);
            glyph.setPresentationTranslationYPx(this.karaokeGlyphRestOffsetPx);
            glyph.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
         }
      }

      void setContentPresentationScale(float scale) {
         float safeScale = Math.max(PASSED_SOURCE_MIN_SCALE, scale);
         if (!(Math.abs(this.contentPresentationScale - safeScale) < 0.001F)) {
            this.contentPresentationScale = safeScale;
            this.markChildLayoutDirty();
            if (!this.usingGlyphViews) {
               this.rowTextView.setPresentationTextScale(this.contentPresentationScale);
            }

            for(int i = 0; i < this.charViews.size(); ++i) {
               ((LyricGlyphRun)this.charViews.get(i)).setPresentationTextScale(this.contentPresentationScale);
            }

            if (this.getWidth() > 0 && this.getHeight() > 0) {
               this.layoutChildrenForPresentationScale();
            }

            this.invalidate();
         }
      }

      void setPressedFeedbackProgress(float progress) {
         float safeProgress = this.clamp01(progress);
         if (!(Math.abs(this.pressedFeedbackProgress - safeProgress) < 0.001F)) {
            this.pressedFeedbackProgress = safeProgress;
            this.markChildLayoutDirty();
            if (this.getWidth() > 0 && this.getHeight() > 0) {
               this.layoutChildrenForPresentationScale();
            }

            this.invalidate();
         }
      }

      void setPressedContentSinkPx(float sinkPx) {
         float safeSink = Math.max(0.0F, sinkPx);
         if (!(Math.abs(this.pressedContentSinkPx - safeSink) < 0.01F)) {
            this.pressedContentSinkPx = safeSink;
            this.markChildLayoutDirty();
            if (this.getWidth() > 0 && this.getHeight() > 0) {
               this.layoutChildrenForPresentationScale();
            }

         }
      }

      void setBlurBoundsOverflowPx(float overflowPx) {
         float safeOverflow = Math.max(0.0F, overflowPx);
         if (!(Math.abs(this.blurBoundsOverflowPx - safeOverflow) < 0.01F)) {
            this.blurBoundsOverflowPx = safeOverflow;
            this.markChildLayoutDirty();
            if (this.getWidth() > 0 && this.getHeight() > 0) {
               this.layoutChildrenForPresentationScale();
            }

            this.invalidate();
         }
      }

      float getContentVisualLeftPx() {
         if (!this.usingGlyphViews) {
            return Math.max(0.0F, (float)this.getLeft() + (float)this.rowTextView.getLeft() + this.rowTextView.getEdgePaddingPx() * this.contentPresentationScale);
         } else {
            float left = Float.POSITIVE_INFINITY;

            for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
               LyricGlyphRun child = (LyricGlyphRun)this.charViews.get(i);
               if (child.getVisibility() == 0) {
                  left = Math.min(left, (float)this.getLeft() + (float)child.getLeft() + child.getEdgePaddingPx() * this.contentPresentationScale);
               }
            }

            return Float.isFinite(left) ? Math.max(0.0F, left) : 0.0F;
         }
      }

      float getContentVisualRightPx() {
         if (!this.usingGlyphViews) {
            float visualLeft = (float)this.getLeft() + (float)this.rowTextView.getLeft() + this.rowTextView.getEdgePaddingPx() * this.contentPresentationScale;
            return Math.max(visualLeft, visualLeft + this.rowTextView.getAdvanceWidthPx() * this.contentPresentationScale);
         } else {
            float right = Float.NEGATIVE_INFINITY;

            for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
               LyricGlyphRun child = (LyricGlyphRun)this.charViews.get(i);
               if (child.getVisibility() == 0) {
                  float visualLeft = (float)this.getLeft() + (float)child.getLeft() + child.getEdgePaddingPx() * this.contentPresentationScale;
                  right = Math.max(right, visualLeft + child.getAdvanceWidthPx() * this.contentPresentationScale);
               }
            }

            return Float.isFinite(right) ? Math.max(0.0F, right) : 0.0F;
         }
      }

      void primeGlyphViewsIfNeeded() {
         if (!this.glyphViewsPrimed) {
            this.applyDefaultGlyphRuns();
            this.glyphViewsPrimed = true;
         }
      }

      private void rebuildCodePointMetrics() {
         int count = this.visibleCharCount;
         this.codePointTextStart = this.ensureIntCapacity(this.codePointTextStart, count + 1);
         this.codePointLeftPx = this.ensureFloatCapacity(this.codePointLeftPx, count);
         this.codePointAdvancePx = this.ensureFloatCapacity(this.codePointAdvancePx, count);
         int utf16Cursor = 0;

         for(int i = 0; i < count; ++i) {
            this.codePointTextStart[i] = utf16Cursor;
            utf16Cursor += Character.charCount(this.rowCodePoints[i]);
         }

         this.codePointTextStart[count] = utf16Cursor;

         for(int i = 0; i < count; ++i) {
            int start = this.codePointTextStart[i];
            int end = this.codePointTextStart[i + 1];
            this.codePointLeftPx[i] = start > 0 ? this.glyphMeasurePaint.measureText(this.currentRowText, 0, start) : 0.0F;
            float right = end > 0 ? this.glyphMeasurePaint.measureText(this.currentRowText, 0, end) : this.codePointLeftPx[i];
            this.codePointAdvancePx[i] = Math.max(0.0F, right - this.codePointLeftPx[i]);
         }

      }

      private void applyDefaultGlyphRuns() {
         this.applyGlyphRunsToViews(this.rebuildLanguageAwareGlyphRuns());
         this.clearKaraokeGlyphLiftTimings();
         this.glyphRunKaraokeTimeline = null;
         this.glyphRunLineStart = Integer.MIN_VALUE;
         this.glyphRunLineCount = Integer.MIN_VALUE;
      }

      private void clearKaraokeGlyphLiftTimings() {
         this.glyphLiftStartTimeMs = this.ensureLongCapacity(this.glyphLiftStartTimeMs, this.visibleGlyphCount);
         this.glyphLiftNaturalDurationMs = this.ensureLongCapacity(this.glyphLiftNaturalDurationMs, this.visibleGlyphCount);
         this.glyphLiftNextStartGapMs = this.ensureLongCapacity(this.glyphLiftNextStartGapMs, this.visibleGlyphCount);
         for(int glyphIndex = 0; glyphIndex < this.visibleGlyphCount; ++glyphIndex) {
            this.glyphLiftStartTimeMs[glyphIndex] = Long.MAX_VALUE;
            this.glyphLiftNaturalDurationMs[glyphIndex] = KARAOKE_GLYPH_LIFT_MIN_DURATION_MS;
            this.glyphLiftNextStartGapMs[glyphIndex] = Long.MIN_VALUE;
         }
      }

      private void configureKaraokeGlyphRuns(@NonNull KaraokeTimeline timeline, int lineCodePointStart, int lineCodePointCount) {
         this.primeGlyphViewsIfNeeded();
         if (this.glyphRunKaraokeTimeline == timeline && this.glyphRunLineStart == lineCodePointStart && this.glyphRunLineCount == lineCodePointCount) {
            return;
         }

         this.applyGlyphRunsToViews(this.rebuildLanguageAwareGlyphRuns());
         this.rebuildKaraokeGlyphLiftTimings(timeline, lineCodePointStart, lineCodePointCount);
         this.glyphRunKaraokeTimeline = timeline;
         this.glyphRunLineStart = lineCodePointStart;
         this.glyphRunLineCount = lineCodePointCount;
      }

      private void rebuildKaraokeGlyphLiftTimings(@NonNull KaraokeTimeline timeline, int lineCodePointStart, int lineCodePointCount) {
         int glyphCount = this.visibleGlyphCount;
         this.glyphLiftStartTimeMs = this.ensureLongCapacity(this.glyphLiftStartTimeMs, glyphCount);
         this.glyphLiftNaturalDurationMs = this.ensureLongCapacity(this.glyphLiftNaturalDurationMs, glyphCount);
         this.glyphLiftNextStartGapMs = this.ensureLongCapacity(this.glyphLiftNextStartGapMs, glyphCount);
         int tokenCount = Math.min(
               Math.min(timeline.tokenTimeMs.length, timeline.tokenStartCodePoint.length),
               Math.min(timeline.tokenEndCodePoint.length, timeline.tokenVisibleCodePointCount.length)
         );
         int safeLineStart = Math.max(0, lineCodePointStart);
         int safeLineEnd = Math.max(safeLineStart, safeLineStart + Math.max(0, lineCodePointCount));
         this.codePointLiftStartMs = this.ensureLongCapacity(this.codePointLiftStartMs, this.visibleCharCount);
         java.util.Arrays.fill(this.codePointLiftStartMs, Long.MAX_VALUE);
         this.glyphLiftLetterSpreadMs = this.ensureLongCapacity(this.glyphLiftLetterSpreadMs, glyphCount);

         for(int glyphIndex = 0; glyphIndex < glyphCount; ++glyphIndex) {
            int localStart = glyphIndex < this.glyphCodePointStart.length ? Math.max(0, this.glyphCodePointStart[glyphIndex]) : glyphIndex;
            int localCount = glyphIndex < this.glyphCodePointCount.length ? Math.max(0, this.glyphCodePointCount[glyphIndex]) : 1;
            int localEnd = Math.min(this.visibleCharCount, localStart + localCount);
            double liftStartMs = Double.POSITIVE_INFINITY;
            double liftEndMs = Double.NEGATIVE_INFINITY;
            int tokenIndex = 0;

            for(int localCodePoint = localStart; localCodePoint < localEnd; ++localCodePoint) {
               int sourceCodePoint = safeLineStart + localCodePoint;
               if (sourceCodePoint >= safeLineEnd) {
                  break;
               }

               while(tokenIndex < tokenCount && timeline.tokenEndCodePoint[tokenIndex] <= sourceCodePoint) {
                  ++tokenIndex;
               }

               if (tokenIndex >= tokenCount || sourceCodePoint < timeline.tokenStartCodePoint[tokenIndex]) {
                  continue;
               }

               int visibleOrdinal = timeline.resolveVisibleOrdinalInToken(sourceCodePoint);
               int visibleCount = Math.max(0, timeline.tokenVisibleCodePointCount[tokenIndex]);
               if (visibleOrdinal < 0 || visibleCount <= 0) {
                  continue;
               }

               long tokenDurationMs = Math.max(1L, this.resolveTokenDurationMs(timeline, tokenIndex, tokenCount));
               double codePointDurationMs = (double)tokenDurationMs / (double)visibleCount;
               double codePointStartMs = (double)timeline.tokenTimeMs[tokenIndex] + codePointDurationMs * (double)visibleOrdinal;
               double codePointEndMs = codePointStartMs + codePointDurationMs;
               if (localCodePoint < this.codePointLiftStartMs.length) {
                  this.codePointLiftStartMs[localCodePoint] = (long)Math.floor(codePointStartMs);
               }
               liftStartMs = Math.min(liftStartMs, codePointStartMs);
               liftEndMs = Math.max(liftEndMs, codePointEndMs);
            }

            if (liftStartMs != Double.POSITIVE_INFINITY && liftEndMs != Double.NEGATIVE_INFINITY && liftEndMs > liftStartMs) {
               this.glyphLiftStartTimeMs[glyphIndex] = (long)Math.floor(liftStartMs);
               this.glyphLiftNaturalDurationMs[glyphIndex] = Math.max(1L, (long)Math.ceil(liftEndMs - liftStartMs));
               long spreadMs = 0L;
               for(int localCodePoint = localStart; localCodePoint < localEnd && localCodePoint < this.codePointLiftStartMs.length; ++localCodePoint) {
                  long codePointStart = this.codePointLiftStartMs[localCodePoint];
                  if (codePointStart != Long.MAX_VALUE) {
                     spreadMs = Math.max(spreadMs, codePointStart - this.glyphLiftStartTimeMs[glyphIndex]);
                  }
               }
               this.glyphLiftLetterSpreadMs[glyphIndex] = spreadMs;
            } else {
               this.glyphLiftStartTimeMs[glyphIndex] = Long.MAX_VALUE;
               this.glyphLiftNaturalDurationMs[glyphIndex] = KARAOKE_GLYPH_LIFT_MIN_DURATION_MS;
               this.glyphLiftLetterSpreadMs[glyphIndex] = 0L;
            }
         }

         for(int glyphIndex = 0; glyphIndex < glyphCount; ++glyphIndex) {
            long startTimeMs = this.glyphLiftStartTimeMs[glyphIndex];
            long nextStartTimeMs = Long.MAX_VALUE;
            for(int nextGlyphIndex = glyphIndex + 1; nextGlyphIndex < glyphCount; ++nextGlyphIndex) {
               long candidateStartTimeMs = this.glyphLiftStartTimeMs[nextGlyphIndex];
               if (candidateStartTimeMs != Long.MAX_VALUE) {
                  nextStartTimeMs = candidateStartTimeMs;
                  break;
               }
            }

            if (nextStartTimeMs == Long.MAX_VALUE) {
               int localStart = glyphIndex < this.glyphCodePointStart.length ? Math.max(0, this.glyphCodePointStart[glyphIndex]) : glyphIndex;
               int localCount = glyphIndex < this.glyphCodePointCount.length ? Math.max(0, this.glyphCodePointCount[glyphIndex]) : 1;
               int sourceCodePointEnd = safeLineStart + localStart + localCount;
               nextStartTimeMs = this.resolveNextKaraokeCodePointStartTimeMs(timeline, tokenCount, sourceCodePointEnd);
            }

            this.glyphLiftNextStartGapMs[glyphIndex] = startTimeMs != Long.MAX_VALUE
                  && nextStartTimeMs != Long.MAX_VALUE
                  && nextStartTimeMs > startTimeMs
                  ? nextStartTimeMs - startTimeMs
                  : Long.MIN_VALUE;
         }
      }

      private long resolveNextKaraokeCodePointStartTimeMs(@NonNull KaraokeTimeline timeline, int tokenCount, int sourceCodePointStart) {
         int codePointCount = timeline.codePointVisibleOrdinalInToken.length;
         int tokenIndex = 0;
         for(int sourceCodePoint = Math.max(0, sourceCodePointStart); sourceCodePoint < codePointCount; ++sourceCodePoint) {
            while(tokenIndex < tokenCount && timeline.tokenEndCodePoint[tokenIndex] <= sourceCodePoint) {
               ++tokenIndex;
            }

            if (tokenIndex >= tokenCount) {
               return Long.MAX_VALUE;
            }

            if (sourceCodePoint < timeline.tokenStartCodePoint[tokenIndex]) {
               continue;
            }

            int visibleOrdinal = timeline.resolveVisibleOrdinalInToken(sourceCodePoint);
            int visibleCount = Math.max(0, timeline.tokenVisibleCodePointCount[tokenIndex]);
            if (visibleOrdinal >= 0 && visibleCount > 0) {
               long tokenDurationMs = Math.max(1L, this.resolveTokenDurationMs(timeline, tokenIndex, tokenCount));
               double codePointDurationMs = (double)tokenDurationMs / (double)visibleCount;
               return (long)Math.floor((double)timeline.tokenTimeMs[tokenIndex] + codePointDurationMs * (double)visibleOrdinal);
            }
         }

         return Long.MAX_VALUE;
      }

      private int rebuildLanguageAwareGlyphRuns() {
         int codePointCount = this.visibleCharCount;
         this.glyphCodePointStart = this.ensureIntCapacity(this.glyphCodePointStart, Math.max(1, codePointCount));
         this.glyphCodePointCount = this.ensureIntCapacity(this.glyphCodePointCount, Math.max(1, codePointCount));
         int runCount = 0;
         int local = 0;

         while(local < codePointCount) {
            int runStart = local;
            int cp = this.rowCodePoints[local];
            if (this.isWordRunStartCodePoint(cp)) {
               ++local;

               while(local < codePointCount) {
                  int next = this.rowCodePoints[local];
                  if (this.isWordRunContinuationCodePoint(next)) {
                     ++local;
                  } else if (this.isWordJoinerCodePoint(next) && local + 1 < codePointCount && this.isWordRunContinuationCodePoint(this.rowCodePoints[local + 1])) {
                     ++local;
                  } else {
                     break;
                  }
               }

               while(local < codePointCount && this.isTrailingWordPunctuationCodePoint(this.rowCodePoints[local])) {
                  ++local;
               }

               while(local < codePointCount && this.isGlyphWhitespaceCodePoint(this.rowCodePoints[local])) {
                  ++local;
               }
            } else if (this.isGlyphWhitespaceCodePoint(cp)) {
               do {
                  ++local;
               } while(local < codePointCount && this.isGlyphWhitespaceCodePoint(this.rowCodePoints[local]));
            } else {
               ++local;
            }

            this.glyphCodePointStart[runCount] = runStart;
            this.glyphCodePointCount[runCount] = Math.max(1, local - runStart);
            ++runCount;
         }

         return runCount;
      }

      private boolean isWordRunStartCodePoint(int cp) {
         if (Character.isDigit(cp)) {
            return true;
         } else if (!Character.isLetter(cp)) {
            return false;
         } else {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK || script == Character.UnicodeScript.CYRILLIC;
         }
      }

      private boolean isWordRunContinuationCodePoint(int cp) {
         int type = Character.getType(cp);
         return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK || this.isWordRunStartCodePoint(cp);
      }

      private boolean isWordJoinerCodePoint(int cp) {
         return cp == 39
               || cp == 8217
               || cp == 700
               || cp == 45
               || cp == 8208
               || cp == 8209
               || cp == 46
               || cp == 47
               || cp == 38;
      }

      private boolean isTrailingWordPunctuationCodePoint(int cp) {
         return cp == 33
               || cp == 34
               || cp == 37
               || cp == 41
               || cp == 44
               || cp == 46
               || cp == 58
               || cp == 59
               || cp == 63
               || cp == 93
               || cp == 125
               || cp == 161
               || cp == 191
               || cp == 8217
               || cp == 8221
               || cp == 8230
               || cp == 12289
               || cp == 12290
               || cp == 65281
               || cp == 65292
               || cp == 65306
               || cp == 65307
               || cp == 65311
               || cp == 65373
               || cp == 65341;
      }

      private boolean isGlyphWhitespaceCodePoint(int cp) {
         return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
      }

      private void applyGlyphRunsToViews(int runCount) {
         this.visibleGlyphCount = Math.max(0, runCount);
         this.ensureCharViewCapacity(this.visibleGlyphCount);
         this.markChildLayoutDirty();

         for(int i = 0; i < this.charViews.size(); ++i) {
            LyricGlyphRun charView = (LyricGlyphRun)this.charViews.get(i);
            if (i < this.visibleGlyphCount) {
               int start = Math.max(0, Math.min(this.visibleCharCount, this.glyphCodePointStart[i]));
               int count = Math.max(0, Math.min(this.visibleCharCount - start, this.glyphCodePointCount[i]));
               charView.setTextContent(count > 0 ? new String(this.rowCodePoints, start, count) : "");
               charView.setPresentationBlurRadiusPx(0.0F);
               charView.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
               if (this.usingGlyphViews && charView.getVisibility() != 0) {
                  charView.setVisibility(0);
               }
            } else if (charView.getVisibility() != 8) {
               charView.setVisibility(8);
            }
         }

         if (this.usingGlyphViews && this.getWidth() > 0 && this.getHeight() > 0) {
            this.measureGlyphChildren();
            this.layoutChildrenForPresentationScale();
            this.invalidate();
         }

         this.appliedGlyphSoftwareBlurRadiusPx = Float.NaN;
      }

      void setRowVisualState(boolean active, int activeColor, int inactiveColor, long activeHdrColor, boolean useActiveHdrColor, float blurRadiusPx) {
         this.karaokeMode = false;
         this.lastKaraokeTimeline = null;
         this.lastKaraokeUseHdrHighlight = false;
         int color = active ? activeColor : inactiveColor;
         this.currentSolidColor = color;
         this.currentUseHdrColor = useActiveHdrColor;
         this.currentHdrColor = activeHdrColor;
         this.currentBlurRadiusPx = blurRadiusPx;
         if (!this.usingGlyphViews && this.visibleCharCount > 0) {
            this.ensureGlyphViewsForCurrentRow();
         }

         if (!this.usingGlyphViews) {
            this.syncUniformBlurState();
            this.applySolidVisualState();
         } else {
            for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
               LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
               if (this.currentUseHdrColor) {
                  glyph.setTextColorLong(this.currentHdrColor);
               } else {
                  glyph.setTextColorInt(this.currentSolidColor);
               }

               glyph.setPresentationBlurRadiusPx(0.0F);
               glyph.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
            }

            this.syncUniformBlurState();
         }
      }

      void setRowHdrVisualState(boolean useHdrColor, long hdrColor) {
         if (this.karaokeMode) {
            return;
         }

         if (this.currentUseHdrColor == useHdrColor && this.currentHdrColor == hdrColor) {
            return;
         }

         this.currentUseHdrColor = useHdrColor;
         this.currentHdrColor = hdrColor;
         if (!this.usingGlyphViews) {
            this.applySolidVisualState();
            return;
         }

         for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
            LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
            if (this.currentUseHdrColor) {
               glyph.setTextColorLong(this.currentHdrColor);
            } else {
               glyph.setTextColorInt(this.currentSolidColor);
            }
         }
      }

      private long resolveStableKaraokePositionMs(@NonNull KaraokeTimeline timeline, int lineCodePointStart, int lineCodePointCount, long positionMs, boolean allowBackstepStabilization) {
         boolean sameKaraokeLine = allowBackstepStabilization
               && this.karaokeMode
               && this.lastKaraokeTimeline == timeline
               && this.lastKaraokeLineStart == lineCodePointStart
               && this.lastKaraokeLineCount == lineCodePointCount
               && this.lastKaraokePositionMs != Long.MIN_VALUE;
         if (sameKaraokeLine && positionMs < this.lastKaraokePositionMs) {
            long backstepMs = this.lastKaraokePositionMs - positionMs;
            if (backstepMs <= KARAOKE_POSITION_BACKSTEP_TOLERANCE_MS) {
               return this.lastKaraokePositionMs;
            }
         }

         return positionMs;
      }

      private long resolveStableKaraokeLiftPositionMs(@NonNull KaraokeTimeline timeline, int lineCodePointStart, int lineCodePointCount, long positionMs, boolean allowBackstepStabilization) {
         boolean sameKaraokeLine = allowBackstepStabilization
               && this.karaokeMode
               && this.lastKaraokeTimeline == timeline
               && this.lastKaraokeLineStart == lineCodePointStart
               && this.lastKaraokeLineCount == lineCodePointCount
               && this.lastKaraokeLiftPositionMs != Long.MIN_VALUE;
         if (sameKaraokeLine && positionMs < this.lastKaraokeLiftPositionMs) {
            long backstepMs = this.lastKaraokeLiftPositionMs - positionMs;
            if (backstepMs <= KARAOKE_POSITION_BACKSTEP_TOLERANCE_MS) {
               return this.lastKaraokeLiftPositionMs;
            }
         }

         return positionMs;
      }

      void setKaraokeHighlight(@NonNull KaraokeTimeline timeline, int lineCodePointStart, int lineCodePointCount, long highlightPositionMs, long liftPositionMs, int revealedColor, int pendingColor, boolean useHdrHighlight, long revealedHdrColor, long pendingHdrColor, float blurRadiusPx, boolean allowBackstepStabilization) {
         if (this.prepDotsMode) {
            return;
         }

         int safeStart = Math.max(0, lineCodePointStart);
         int safeCount = Math.max(0, lineCodePointCount);
         long stablePositionMs = this.resolveStableKaraokePositionMs(timeline, safeStart, safeCount, highlightPositionMs, allowBackstepStabilization);
         long stableLiftPositionMs = this.resolveStableKaraokeLiftPositionMs(timeline, safeStart, safeCount, liftPositionMs, allowBackstepStabilization);
         boolean unchanged = this.karaokeMode
               && this.lastKaraokeTimeline == timeline
               && safeStart == this.lastKaraokeLineStart
               && safeCount == this.lastKaraokeLineCount
               && stablePositionMs == this.lastKaraokePositionMs
               && stableLiftPositionMs == this.lastKaraokeLiftPositionMs
               && revealedColor == this.lastKaraokeRevealColor
               && pendingColor == this.lastKaraokePendingColor
               && useHdrHighlight == this.lastKaraokeUseHdrHighlight
               && revealedHdrColor == this.lastKaraokeRevealHdrColor
               && pendingHdrColor == this.lastKaraokePendingHdrColor
               && Math.abs(blurRadiusPx - this.lastKaraokeBlurRadiusPx) < 0.01F;
         if (unchanged) {
            return;
         }

         this.karaokeMode = true;
         this.lastKaraokeTimeline = timeline;
         this.lastKaraokeLineStart = safeStart;
         this.lastKaraokeLineCount = safeCount;
         this.lastKaraokePositionMs = stablePositionMs;
         this.lastKaraokeLiftPositionMs = stableLiftPositionMs;
         this.lastKaraokeRevealColor = revealedColor;
         this.lastKaraokePendingColor = pendingColor;
         this.lastKaraokeUseHdrHighlight = useHdrHighlight;
         this.lastKaraokeRevealHdrColor = revealedHdrColor;
         this.lastKaraokePendingHdrColor = pendingHdrColor;
         this.lastKaraokeBlurRadiusPx = blurRadiusPx;
         this.currentBlurRadiusPx = Math.max(0.0F, blurRadiusPx);
         this.configureKaraokeGlyphRuns(timeline, safeStart, safeCount);
         this.ensureGlyphViewsForCurrentRow();

         int tokenCount = Math.min(timeline.tokenTimeMs.length, timeline.tokenEndCodePoint.length);
         int safeVisibleCount = Math.max(0, Math.min(this.visibleCharCount, safeCount));
         float rowScanXPx = this.resolveTimelineScanX(timeline, tokenCount, safeStart, safeVisibleCount, safeCount, this.codePointLeftPx, this.codePointAdvancePx, stablePositionMs);
         for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
            LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
            int runStart = i < this.glyphCodePointStart.length ? this.glyphCodePointStart[i] : i;
            if (runStart >= safeVisibleCount) {
               if (useHdrHighlight) {
                  glyph.setTextColorLong(revealedHdrColor);
               } else {
                  glyph.setTextColorInt(revealedColor);
               }

               glyph.setTranslationY(0.0F);
               glyph.setPresentationTranslationYPx(0.0F);
               glyph.setPresentationBlurRadiusPx(0.0F);
               glyph.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
            } else {
               float runLeftPx = runStart < this.codePointLeftPx.length ? this.codePointLeftPx[runStart] : 0.0F;
               float localScanX = rowScanXPx - runLeftPx;
               if (localScanX <= 0.0F) {
                  if (useHdrHighlight) {
                     glyph.setTextColorLong(pendingHdrColor);
                  } else {
                     glyph.setTextColorInt(pendingColor);
                  }
               } else if (localScanX >= glyph.getAdvanceWidthPx() + this.karaokeScanFeatherPx) {
                  if (useHdrHighlight) {
                     glyph.setTextColorLong(revealedHdrColor);
                  } else {
                     glyph.setTextColorInt(revealedColor);
                  }
               } else {
                  if (useHdrHighlight) {
                     glyph.setTextRevealScanWindowLong(pendingHdrColor, revealedHdrColor, localScanX, this.karaokeScanFeatherPx);
                  } else {
                     glyph.setTextRevealScanWindow(pendingColor, revealedColor, localScanX, this.karaokeScanFeatherPx);
                  }
               }

               glyph.setTranslationY(0.0F);
               glyph.setPresentationTranslationYPx(this.resolveKaraokeGlyphLiftOffsetPx(i, stableLiftPositionMs));
               this.applyKaraokeLetterLifts(glyph, i, runStart, stableLiftPositionMs);
               glyph.setPresentationBlurRadiusPx(0.0F);
               glyph.setLongNoteGlow(
                     this.resolveKaraokeGlyphLongNoteGlowStrength(i, stableLiftPositionMs),
                     this.karaokeGlyphLongNoteGlowInnerRadiusPx,
                     this.karaokeGlyphLongNoteGlowOuterRadiusPx
               );
            }
         }

         this.syncUniformBlurState();
      }

      private float resolveKaraokeGlyphLiftOffsetPx(int glyphIndex, long positionMs) {
         return this.resolveKaraokeGlyphLiftOffsetPx(glyphIndex, 0L, positionMs, 0.0F);
      }

      /**
       * A word's letters rise one after another: each starts its own rise {@code delayMs} after
       * the word's, along the word's own curve. A held word (a long note) instead rises whole,
       * higher and with more rebound ({@link #KARAOKE_WORD_LONG_NOTE_LIFT_SCALE}), as far as the
       * row's frame leaves room for it with its glow.
       */
      private void applyKaraokeLetterLifts(@NonNull LyricGlyphRun glyph, int glyphIndex, int runStart, long positionMs) {
         int letters = glyph.getLetterCount();
         if (letters < 2 || glyphIndex < 0 || glyphIndex >= this.glyphLiftStartTimeMs.length) {
            return;
         }

         long runStartMs = this.glyphLiftStartTimeMs[glyphIndex];
         if (runStartMs == Long.MAX_VALUE) {
            return;
         }

         long nextStartGapMs = glyphIndex < this.glyphLiftNextStartGapMs.length ? this.glyphLiftNextStartGapMs[glyphIndex] : Long.MIN_VALUE;
         if (nextStartGapMs > KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS && this.karaokeGlyphLongNoteExtraLiftPx > 0.001F) {
            glyph.setPresentationTranslationYPx(this.resolveKaraokeGlyphLiftOffsetPx(glyphIndex, 0L, positionMs,
                  this.resolveWordLongNoteApexPx(nextStartGapMs)));
            return;
         }

         if (this.letterLiftScratchPx.length < letters) {
            this.letterLiftScratchPx = new float[letters];
         }

         long delayMs = 0L;
         for(int letter = 0; letter < letters; ++letter) {
            int codePoint = runStart + glyph.getLetterCodePointIndex(letter);
            long letterStartMs = codePoint >= 0 && codePoint < this.codePointLiftStartMs.length
                  ? this.codePointLiftStartMs[codePoint]
                  : Long.MAX_VALUE;
            // Untimed (a space, punctuation): it goes with the letter before it.
            if (letterStartMs != Long.MAX_VALUE) {
               delayMs = Math.max(0L, letterStartMs - runStartMs);
            }

            this.letterLiftScratchPx[letter] = this.resolveKaraokeGlyphLiftOffsetPx(glyphIndex, delayMs, positionMs, 0.0F);
         }

         glyph.setLetterPresentationTranslationYPx(this.letterLiftScratchPx, letters);
      }

      /** The long note's own peak height (how far above the line), as the word's curve gives it. */
      private float resolveLongNoteApexPx(long nextStartGapMs) {
         float durationRatio = Math.max(1.0F, (float)nextStartGapMs / (float)Math.max(1L, KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS));
         float liftMultiplier = Math.min(KARAOKE_GLYPH_LONG_NOTE_MAX_LIFT_MULTIPLIER, (float)Math.sqrt((double)durationRatio));
         return this.karaokeGlyphLongNoteExtraLiftPx * liftMultiplier;
      }

      /**
       * A held word's peak: 20 % above the usual one, though never so high that its glow's
       * visible spread (two sigma) passes the row's frame, which a blurred row draws inside of;
       * never below the usual peak either.
       */
      private float resolveWordLongNoteApexPx(long nextStartGapMs) {
         float usual = this.resolveLongNoteApexPx(nextStartGapMs);
         float scale = Math.max(0.0F, this.contentPresentationScale);
         float above = (float)this.getHeight() * 0.5F - this.charTextPx * 0.6F * scale;
         float glowSigma = 0.57735F * this.karaokeGlyphLongNoteGlowOuterRadiusPx * scale + 0.5F;
         float room = above - 2.0F * glowSigma - this.karaokeGlyphLongNoteBouncePx * KARAOKE_WORD_LONG_NOTE_LIFT_SCALE;
         return Math.max(usual, Math.min(usual * KARAOKE_WORD_LONG_NOTE_LIFT_SCALE, room));
      }

      /** {@code apexOverridePx} > 0: a held word's own peak (and a rebound grown in step). */
      private float resolveKaraokeGlyphLiftOffsetPx(int glyphIndex, long delayMs, long positionMs, float apexOverridePx) {
         if (glyphIndex < 0 || glyphIndex >= this.glyphLiftStartTimeMs.length || glyphIndex >= this.glyphLiftNaturalDurationMs.length) {
            return this.karaokeGlyphRestOffsetPx;
         }

         long runStartTimeMs = this.glyphLiftStartTimeMs[glyphIndex];
         if (runStartTimeMs == Long.MAX_VALUE) {
            return this.karaokeGlyphRestOffsetPx;
         }

         long startTimeMs = runStartTimeMs + Math.max(0L, delayMs);
         if (positionMs <= startTimeMs) {
            return this.karaokeGlyphRestOffsetPx;
         }

         long elapsedMs = Math.max(0L, positionMs - startTimeMs);
         long nextStartGapMs = glyphIndex < this.glyphLiftNextStartGapMs.length ? this.glyphLiftNextStartGapMs[glyphIndex] : Long.MIN_VALUE;
         boolean longNote = nextStartGapMs > KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS;
         if (!longNote || this.karaokeGlyphLongNoteExtraLiftPx <= 0.001F) {
            long durationMs = this.resolveKaraokeGlyphLiftDurationMs(glyphIndex);
            float progress = this.clamp01((float)elapsedMs / (float)Math.max(1L, durationMs));
            return this.lerp(this.karaokeGlyphRestOffsetPx, 0.0F, this.smootherStep(progress));
         }

         float usualApexPx = this.resolveLongNoteApexPx(nextStartGapMs);
         float apexOffsetPx = -(apexOverridePx > 0.0F ? apexOverridePx : usualApexPx);
         float bouncePx = this.karaokeGlyphLongNoteBouncePx
               * (apexOverridePx > 0.0F ? apexOverridePx / Math.max(0.001F, usualApexPx) : 1.0F);
         long riseSpanMs = Math.max(1L, nextStartGapMs - Math.max(0L, delayMs));
         if (elapsedMs <= riseSpanMs) {
            float riseProgress = this.smootherStep((float)elapsedMs / (float)riseSpanMs);
            return this.lerp(this.karaokeGlyphRestOffsetPx, apexOffsetPx, riseProgress);
         }

         long releaseElapsedMs = elapsedMs - riseSpanMs;
         if (releaseElapsedMs < KARAOKE_GLYPH_LONG_NOTE_FALL_MS) {
            float fallProgress = this.smootherStep((float)releaseElapsedMs / (float)Math.max(1L, KARAOKE_GLYPH_LONG_NOTE_FALL_MS));
            return this.lerp(apexOffsetPx, bouncePx, fallProgress);
         }

         long settleElapsedMs = releaseElapsedMs - KARAOKE_GLYPH_LONG_NOTE_FALL_MS;
         float settleProgress = this.smootherStep((float)settleElapsedMs / (float)Math.max(1L, KARAOKE_GLYPH_LONG_NOTE_SETTLE_MS));
         return this.lerp(bouncePx, 0.0F, settleProgress);
      }

      private float resolveKaraokeGlyphLongNoteGlowStrength(int glyphIndex, long positionMs) {
         if (glyphIndex < 0
               || glyphIndex >= this.glyphLiftStartTimeMs.length
               || glyphIndex >= this.glyphLiftNextStartGapMs.length
               || this.karaokeGlyphLongNoteGlowOuterRadiusPx <= 0.01F) {
            return 0.0F;
         }

         long startTimeMs = this.glyphLiftStartTimeMs[glyphIndex];
         long holdDurationMs = this.glyphLiftNextStartGapMs[glyphIndex];
         if (startTimeMs == Long.MAX_VALUE
               || holdDurationMs <= KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS
               || positionMs <= startTimeMs) {
            return 0.0F;
         }

         long elapsedMs = Math.max(0L, positionMs - startTimeMs);
         if (elapsedMs <= holdDurationMs) {
            long riseDurationMs = Math.min(
                  KARAOKE_GLYPH_LONG_NOTE_GLOW_RISE_MAX_MS,
                  Math.max(180L, Math.round((float)holdDurationMs * 0.22F))
            );
            float onset = this.smootherStep((float)elapsedMs / (float)Math.max(1L, riseDurationMs));
            float holdProgress = this.clamp01((float)elapsedMs / (float)Math.max(1L, holdDurationMs));
            float liftCoupling = this.lerp(0.76F, 0.94F, this.smootherStep(holdProgress));
            // One very shallow breath across the held note reads as organic light rather than
            // a looping pulse, and stays visually attached to the existing upward motion.
            float breath = 1.0F + 0.06F * (float)Math.sin(Math.PI * (double)holdProgress);
            return this.clamp01(onset * liftCoupling * breath);
         }

         long releaseElapsedMs = elapsedMs - holdDurationMs;
         long releaseDurationMs = KARAOKE_GLYPH_LONG_NOTE_FALL_MS + KARAOKE_GLYPH_LONG_NOTE_SETTLE_MS;
         float release = this.smootherStep((float)releaseElapsedMs / (float)Math.max(1L, releaseDurationMs));
         return this.clamp01(0.94F * (1.0F - release));
      }

      private long resolveKaraokeGlyphLiftDurationMs(int glyphIndex) {
         long naturalDurationMs = glyphIndex >= 0 && glyphIndex < this.glyphLiftNaturalDurationMs.length
               ? this.glyphLiftNaturalDurationMs[glyphIndex]
               : KARAOKE_GLYPH_LIFT_MIN_DURATION_MS;
         long averageDurationWithHeadroomMs = this.glyphRunKaraokeTimeline != null
               ? Math.max(1L, Math.round(
                     (float)this.glyphRunKaraokeTimeline.averageGlyphIntervalMs
                           * KARAOKE_GLYPH_AVERAGE_DURATION_HEADROOM_RATIO))
               : KARAOKE_GLYPH_LIFT_MIN_DURATION_MS;
         long lineAdaptiveMinimumMs = Math.max(
               KARAOKE_GLYPH_LIFT_MIN_DURATION_MS,
               averageDurationWithHeadroomMs);
         long durationMs = Math.max(lineAdaptiveMinimumMs, naturalDurationMs);
         if (glyphIndex >= 0 && glyphIndex < this.glyphLiftNextStartGapMs.length) {
            long nextStartGapMs = this.glyphLiftNextStartGapMs[glyphIndex];
            if (nextStartGapMs > KARAOKE_GLYPH_LONG_NOTE_THRESHOLD_MS) {
               durationMs = nextStartGapMs + KARAOKE_GLYPH_LONG_NOTE_FALL_MS + KARAOKE_GLYPH_LONG_NOTE_SETTLE_MS;
            }
         }

         return durationMs;
      }

      boolean hasInFlightKaraokeGlyphLift(long positionMs) {
         if (this.karaokeGlyphRestOffsetPx <= 0.001F) {
            return false;
         }

         int glyphCount = Math.min(this.visibleGlyphCount, Math.min(this.glyphLiftStartTimeMs.length, this.glyphLiftNaturalDurationMs.length));
         for(int glyphIndex = 0; glyphIndex < glyphCount; ++glyphIndex) {
            long startTimeMs = this.glyphLiftStartTimeMs[glyphIndex];
            if (startTimeMs == Long.MAX_VALUE || positionMs < startTimeMs) {
               continue;
            }

            long durationMs = this.resolveKaraokeGlyphLiftDurationMs(glyphIndex);
            long spreadMs = glyphIndex < this.glyphLiftLetterSpreadMs.length ? this.glyphLiftLetterSpreadMs[glyphIndex] : 0L;
            if (positionMs - startTimeMs < durationMs + spreadMs) {
               return true;
            }
         }

         return false;
      }

      void setRowBlurRadiusPx(float blurRadiusPx) {
         float safeBlurRadiusPx = Math.max(0.0F, blurRadiusPx);
         if (!(Math.abs(this.currentBlurRadiusPx - safeBlurRadiusPx) < 0.01F)) {
            this.currentBlurRadiusPx = safeBlurRadiusPx;
            if (!this.usingGlyphViews && this.visibleCharCount > 0) {
               this.ensureGlyphViewsForCurrentRow();
            }

            this.syncUniformBlurState();
         }
      }

      private void collapseGlyphViewsToSolidText() {
         if (!this.usingGlyphViews) {
            this.applySolidVisualState();
         } else {
            this.collapseGlyphViews();
            this.measureRowTextChild();
            if (this.getWidth() > 0 && this.getHeight() > 0) {
               this.layoutChildrenForPresentationScale();
               this.invalidate();
            } else {
               this.requestLayout();
            }

            this.syncUniformBlurState();
            this.applySolidVisualState();
         }
      }

      private float resolveTimelineScanX(@NonNull KaraokeTimeline timeline, int tokenCount, int rowCodePointStart, int visibleRowCodePointCount, int sourceRowCodePointCount, @NonNull float[] charLeftPx, @NonNull float[] charAdvancePx, long positionMs) {
         if (tokenCount <= 0 || visibleRowCodePointCount <= 0) {
            return -this.karaokeScanFeatherPx;
         } else {
            int tokenIndex = 0;

            for(int i = 0; i < tokenCount; ++i) {
               if (positionMs >= timeline.tokenTimeMs[i]) {
                  tokenIndex = i;
               } else {
                  break;
               }
            }

            int rowStart = rowCodePointStart;
            int rowEnd = rowStart + Math.max(0, sourceRowCodePointCount);
            int tokenStart = tokenIndex < timeline.tokenStartCodePoint.length ? timeline.tokenStartCodePoint[tokenIndex] : 0;
            int tokenEnd = tokenIndex < timeline.tokenEndCodePoint.length ? timeline.tokenEndCodePoint[tokenIndex] : rowEnd;
            if (tokenEnd <= rowStart) {
               return -this.karaokeScanFeatherPx;
            } else if (tokenStart >= rowEnd) {
               int lastVisibleIndex = Math.max(0, Math.min(visibleRowCodePointCount - 1, Math.min(charLeftPx.length, charAdvancePx.length) - 1));
               return charLeftPx[lastVisibleIndex] + charAdvancePx[lastVisibleIndex] + this.karaokeScanFeatherPx;
            }

            return this.resolveTokenScanX(timeline, tokenIndex, tokenCount, rowCodePointStart, visibleRowCodePointCount, sourceRowCodePointCount, charLeftPx, charAdvancePx, positionMs);
         }
      }

      private float resolveTokenScanX(@NonNull KaraokeTimeline timeline, int tokenIndex, int tokenCount, int rowCodePointStart, int visibleRowCodePointCount, int sourceRowCodePointCount, @NonNull float[] charLeftPx, @NonNull float[] charAdvancePx, long positionMs) {
         int visibleCount = tokenIndex < timeline.tokenVisibleCodePointCount.length ? timeline.tokenVisibleCodePointCount[tokenIndex] : 0;
         if (visibleCount <= 0 || visibleRowCodePointCount <= 0) {
            return 0.0F;
         } else {
            long tokenStartMs = timeline.tokenTimeMs[tokenIndex];
            float tokenDurationMs = (float)this.resolveTokenDurationMs(timeline, tokenIndex, tokenCount);
            float tokenLocal = this.clamp01((float)(positionMs - tokenStartMs) / Math.max(1.0F, tokenDurationMs));
            float visibleProgress = tokenLocal * (float)visibleCount;
            int targetOrdinal = Math.min(visibleCount - 1, Math.max(0, (int)Math.floor((double)visibleProgress)));
            float targetLocal = this.clamp01(visibleProgress - (float)targetOrdinal);
            int firstVisibleIndex = -1;
            int lastVisibleIndex = -1;
            int targetIndex = -1;
            int minOrdinal = Integer.MAX_VALUE;
            int maxOrdinal = Integer.MIN_VALUE;
            int tokenStart = tokenIndex < timeline.tokenStartCodePoint.length ? timeline.tokenStartCodePoint[tokenIndex] : 0;
            int tokenEnd = tokenIndex < timeline.tokenEndCodePoint.length ? timeline.tokenEndCodePoint[tokenIndex] : rowCodePointStart + sourceRowCodePointCount;

            for(int i = 0; i < visibleRowCodePointCount; ++i) {
               int globalCodePoint = rowCodePointStart + i;
               if (globalCodePoint >= tokenStart && globalCodePoint < tokenEnd) {
                  int ordinal = timeline.resolveVisibleOrdinalInToken(globalCodePoint);
                  if (ordinal >= 0) {
                     if (firstVisibleIndex < 0) {
                        firstVisibleIndex = i;
                     }

                     lastVisibleIndex = i;
                     minOrdinal = Math.min(minOrdinal, ordinal);
                     maxOrdinal = Math.max(maxOrdinal, ordinal);
                     if (ordinal == targetOrdinal) {
                        targetIndex = i;
                     }
                  }
               }
            }

            if (firstVisibleIndex >= 0 && lastVisibleIndex >= 0) {
               if (targetOrdinal < minOrdinal) {
                  return charLeftPx[firstVisibleIndex] - this.karaokeScanFeatherPx;
               } else if (targetOrdinal <= maxOrdinal && !(visibleProgress >= (float)maxOrdinal + 1.0F)) {
                  return targetIndex < 0 ? charLeftPx[firstVisibleIndex] - this.karaokeScanFeatherPx : charLeftPx[targetIndex] + charAdvancePx[targetIndex] * targetLocal;
               } else {
                  return charLeftPx[lastVisibleIndex] + charAdvancePx[lastVisibleIndex] + this.karaokeScanFeatherPx;
               }
            } else {
               return visibleProgress <= 0.0F ? -this.karaokeScanFeatherPx : charLeftPx[Math.max(0, Math.min(visibleRowCodePointCount - 1, charLeftPx.length - 1))] + this.karaokeScanFeatherPx;
            }
         }
      }

      private long resolveTokenDurationMs(@NonNull KaraokeTimeline timeline, int tokenIndex, int tokenCount) {
         long tokenStartMs = timeline.tokenTimeMs[tokenIndex];
         if (tokenIndex + 1 < tokenCount) {
            long nextStartMs = timeline.tokenTimeMs[tokenIndex + 1];
            if (nextStartMs > tokenStartMs) {
               return nextStartMs - tokenStartMs;
            }
         }

         if (tokenIndex > 0) {
            long previousStartMs = timeline.tokenTimeMs[tokenIndex - 1];
            if (tokenStartMs > previousStartMs) {
               return tokenStartMs - previousStartMs;
            }
         }

         return 180L;
      }

      void clearPrepDotsProgressState() {
         this.prepDotsProgressActive = false;
         this.lastPrepDotsFinalStageProgress = -1.0F;
         this.lastPrepDotsPhaseBase = 0.0F;
      }

      void applyPrepDotsProgress(float progress, float finalStageProgress, float phaseBase, boolean stabilizeVisualProgress) {
         if (this.prepDotsMode && this.visibleCharCount > 0) {
            this.ensureGlyphViewsForCurrentRow();
            if (!(progress < 0.0F) && !(progress >= 1.0F)) {
               float sequenceProgress = this.clamp01(phaseBase);
               float stableFinalStageProgress = finalStageProgress >= 0.0F ? this.clamp01(finalStageProgress) : -1.0F;
               if (stabilizeVisualProgress) {
                  if (this.prepDotsProgressActive) {
                     sequenceProgress = Math.max(this.lastPrepDotsPhaseBase, sequenceProgress);
                     if (stableFinalStageProgress >= 0.0F || this.lastPrepDotsFinalStageProgress >= 0.0F) {
                        stableFinalStageProgress = Math.max(Math.max(0.0F, this.lastPrepDotsFinalStageProgress), Math.max(0.0F, stableFinalStageProgress));
                     }
                  }

                  this.prepDotsProgressActive = true;
                  this.lastPrepDotsPhaseBase = sequenceProgress;
                  this.lastPrepDotsFinalStageProgress = stableFinalStageProgress;
               } else {
                  this.prepDotsProgressActive = true;
                  this.lastPrepDotsPhaseBase = sequenceProgress;
                  this.lastPrepDotsFinalStageProgress = stableFinalStageProgress;
               }

               float rowScale = 1.0F;
               float rowAlpha = 1.0F;
               float rowLift = 0.0F;
               float finalVanishProgress = 0.0F;
               float finalBoostedScale = 1.0F + INTRO_PREP_FINAL_SCALE_BOOST;
               if (stableFinalStageProgress >= 0.0F) {
                  float p = stableFinalStageProgress;
                  if (p < 0.62F) {
                     float t = this.easeOut(p / 0.62F);
                     rowScale = this.lerp(1.0F, finalBoostedScale, t);
                     rowLift = this.lerp(0.0F, -INTRO_PREP_FINAL_LIFT_PX, t);
                  } else {
                     float t = this.clamp01((p - 0.62F) / 0.38F);
                     float shrinkEase = t * t;
                     finalVanishProgress = shrinkEase;
                     rowScale = this.lerp(finalBoostedScale, INTRO_PREP_FINAL_VANISH_SCALE, shrinkEase);
                     rowAlpha = 1.0F - shrinkEase;
                     rowLift = this.lerp(-INTRO_PREP_FINAL_LIFT_PX, 0.0F, shrinkEase);
                  }
               }

               for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
                  LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
                  if (glyph.getVisibility() == 0) {
                     float lead = this.resolveDotLead(sequenceProgress, i);
                     float idleAlpha = INTRO_PREP_DOT_IDLE_ALPHA_BASE;
                     float activeAlpha = 0.88F;
                     float alpha = this.lerp(idleAlpha, activeAlpha, lead);
                     float dotScale = (INTRO_PREP_DOT_BASE_SCALE + lead * INTRO_PREP_DOT_ACTIVE_BOOST) * rowScale;
                     float blurRadiusPx = this.resolvePrepDotBlurRadiusPx(lead, rowScale, finalVanishProgress);
                     glyph.setAlpha(this.clamp01(alpha * rowAlpha));
                     glyph.setScaleX(dotScale);
                     glyph.setScaleY(dotScale);
                     glyph.setPresentationTranslationYPx(0.0F);
                     glyph.setTranslationY(rowLift);
                     glyph.setPresentationBlurRadiusPx(blurRadiusPx);
                     glyph.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
                  }
               }
            } else {
               this.clearPrepDotsProgressState();
               this.hidePrepGlyphTransforms(finalStageProgress >= 1.0F ? INTRO_PREP_FINAL_VANISH_SCALE : INTRO_PREP_DOT_BASE_SCALE);
            }
         } else {
            this.clearPrepDotsProgressState();
            this.resetGlyphTransforms();
         }
      }

      private void resetGlyphTransforms() {
         for(int i = 0; i < this.charViews.size(); ++i) {
            LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
            glyph.setAlpha(1.0F);
            glyph.setScaleX(1.0F);
            glyph.setScaleY(1.0F);
            glyph.setTranslationY(0.0F);
            glyph.setPresentationTranslationYPx(0.0F);
            glyph.setPresentationBlurRadiusPx(0.0F);
            glyph.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
         }

      }

      private void hidePrepGlyphTransforms(float hiddenScale) {
         float scale = Math.max(0.0F, hiddenScale);
         for(int i = 0; i < this.charViews.size(); ++i) {
            LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
            glyph.setAlpha(0.0F);
            glyph.setScaleX(scale);
            glyph.setScaleY(scale);
            glyph.setTranslationY(0.0F);
            glyph.setPresentationTranslationYPx(0.0F);
            glyph.setPresentationBlurRadiusPx(0.0F);
            glyph.setLongNoteGlow(0.0F, this.karaokeGlyphLongNoteGlowInnerRadiusPx, this.karaokeGlyphLongNoteGlowOuterRadiusPx);
         }

      }

      protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
         int width = MeasureSpec.getSize(widthMeasureSpec);
         int height = MeasureSpec.getSize(heightMeasureSpec);
         int childW = MeasureSpec.makeMeasureSpec(0, 0);
         int childH = MeasureSpec.makeMeasureSpec(0, 0);
         if (this.usingGlyphViews) {
            for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
               LyricGlyphRun child = (LyricGlyphRun)this.charViews.get(i);
               if (child.getVisibility() == 0) {
                  child.measure(childW, childH);
               }
            }
         } else {
            this.rowTextView.measure(childW, childH);
         }

         // Glyph sizes may have changed; the next layout must place them again even when this
         // row keeps its frame.
         this.markChildLayoutDirty();
         this.setMeasuredDimension(width, height);
      }

      protected void onLayout(boolean changed, int l, int t, int r, int b) {
         int width = this.getWidth();
         int height = this.getHeight();
         if (width > 0 && height > 0) {
            this.layoutChildrenForPresentationScale();
         }
      }

      private void layoutChildrenForPresentationScale() {
         int width = this.getWidth();
         int height = this.getHeight();
         if (width > 0 && height > 0) {
            if (!this.shouldLayoutChildren(width, height)) {
               return;
            }

            this.childLayoutDirty = false;
            this.lastChildLayoutWidth = width;
            this.lastChildLayoutHeight = height;
            this.lastChildLayoutGlyphMode = this.usingGlyphViews;
            this.lastChildLayoutScale = this.contentPresentationScale;
            this.lastChildLayoutPressedProgress = this.pressedFeedbackProgress;
            this.lastChildLayoutOverflowPx = this.blurBoundsOverflowPx;
            int pressedOffsetY = Math.round(this.pressedContentSinkPx * this.pressedFeedbackProgress);
            float contentLeft = this.blurBoundsOverflowPx;
            float contentRight = Math.max(contentLeft + 1.0F, (float)width - this.blurBoundsOverflowPx);
            if (!this.usingGlyphViews) {
               int childW = this.rowTextView.getMeasuredWidth();
               int childH = this.rowTextView.getMeasuredHeight();
               int scaledChildW = Math.max(1, Math.round((float)childW * this.contentPresentationScale));
               int scaledChildH = Math.max(1, Math.round((float)childH * this.contentPresentationScale));
               int childTop = (height - scaledChildH) / 2 + pressedOffsetY;
               int childLeft = Math.round(contentLeft - this.rowTextView.getEdgePaddingPx() * this.contentPresentationScale);
               this.rowTextView.layout(childLeft, childTop, childLeft + scaledChildW, childTop + scaledChildH);
            } else {
               this.laidOutGlyphCount = 0;
               for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
                  LyricGlyphRun child = (LyricGlyphRun)this.charViews.get(i);
                  this.laidOutGlyphCount = i + 1;
                  if (child.getVisibility() == 0) {
                     int childW = child.getMeasuredWidth();
                     int childH = child.getMeasuredHeight();
                     int scaledChildW = Math.max(1, Math.round((float)childW * this.contentPresentationScale));
                     int scaledChildH = Math.max(1, Math.round((float)childH * this.contentPresentationScale));
                     int childTop = (height - scaledChildH) / 2 + pressedOffsetY;
                     float glyphLeft = contentLeft + this.resolveGlyphRunLeftPx(i) * this.contentPresentationScale;
                     int childLeft = Math.round(glyphLeft - child.getEdgePaddingPx() * this.contentPresentationScale);
                     child.layout(childLeft, childTop, childLeft + scaledChildW, childTop + scaledChildH);
                     if (glyphLeft + child.getAdvanceWidthPx() * this.contentPresentationScale >= contentRight + child.getEdgePaddingPx() * this.contentPresentationScale) {
                        break;
                     }
                  }
               }

            }
         }
      }

      private boolean shouldLayoutChildren(int width, int height) {
         return this.childLayoutDirty
               || this.lastChildLayoutWidth != width
               || this.lastChildLayoutHeight != height
               || this.lastChildLayoutGlyphMode != this.usingGlyphViews
               || Math.abs(this.lastChildLayoutScale - this.contentPresentationScale) > 0.001F
               || Math.abs(this.lastChildLayoutPressedProgress - this.pressedFeedbackProgress) > 0.001F
               || Math.abs(this.lastChildLayoutOverflowPx - this.blurBoundsOverflowPx) > 0.01F;
      }

      private void markChildLayoutDirty() {
         this.childLayoutDirty = true;
      }

      private void ensureGlyphViewsForCurrentRow() {
         if (!this.usingGlyphViews) {
            this.primeGlyphViewsIfNeeded();
            int required = this.visibleGlyphCount;

            for(int i = 0; i < this.charViews.size(); ++i) {
               LyricGlyphRun charView = (LyricGlyphRun)this.charViews.get(i);
               if (i < required) {
                  if (charView.getVisibility() != 0) {
                     charView.setVisibility(0);
                  }

                  if (this.currentUseHdrColor) {
                     charView.setTextColorLong(this.currentHdrColor);
                  } else {
                     charView.setTextColorInt(this.currentSolidColor);
                  }
               } else if (charView.getVisibility() != 8) {
                  charView.setVisibility(8);
               }
            }

            this.rowTextView.setVisibility(8);
            this.usingGlyphViews = true;
            this.appliedGlyphSoftwareBlurRadiusPx = Float.NaN;
            this.markChildLayoutDirty();
            if (this.getWidth() > 0 && this.getHeight() > 0) {
               this.measureGlyphChildren();
               this.layoutChildrenForPresentationScale();
               this.invalidate();
            } else {
               this.requestLayout();
            }

            this.syncUniformBlurState();
         }
      }

      private void collapseGlyphViews() {
         if (!this.usingGlyphViews && this.rowTextView.getVisibility() != 0) {
            this.rowTextView.setVisibility(0);
         }

         this.usingGlyphViews = false;
         this.rowTextView.setVisibility(0);
         this.appliedGlyphSoftwareBlurRadiusPx = Float.NaN;
         this.applyRowRenderEffectBlur(0.0F);
         this.markChildLayoutDirty();

         for(int i = 0; i < this.charViews.size(); ++i) {
            LyricGlyphRun charView = (LyricGlyphRun)this.charViews.get(i);
            if (charView.getVisibility() != 8) {
               charView.setVisibility(8);
            }
         }
      }

      private void measureRowTextChild() {
         int childW = MeasureSpec.makeMeasureSpec(0, 0);
         int childH = MeasureSpec.makeMeasureSpec(0, 0);
         this.rowTextView.measure(childW, childH);
      }

      private void measureGlyphChildren() {
         int childW = MeasureSpec.makeMeasureSpec(0, 0);
         int childH = MeasureSpec.makeMeasureSpec(0, 0);

         for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
            LyricGlyphRun child = (LyricGlyphRun)this.charViews.get(i);
            if (child.getVisibility() == 0) {
               child.measure(childW, childH);
            }
         }

      }

      private void layoutGlyphChildren() {
         int width = this.getWidth();
         int height = this.getHeight();
         if (width > 0 && height > 0) {
            float contentLeft = this.blurBoundsOverflowPx;
            float contentRight = Math.max(contentLeft + 1.0F, (float)width - this.blurBoundsOverflowPx);

            this.laidOutGlyphCount = 0;
            for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
               LyricGlyphRun child = (LyricGlyphRun)this.charViews.get(i);
               this.laidOutGlyphCount = i + 1;
               if (child.getVisibility() == 0) {
                  int childW = child.getMeasuredWidth();
                  int childH = child.getMeasuredHeight();
                  int childTop = (height - childH) / 2;
                  float glyphLeft = contentLeft + this.resolveGlyphRunLeftPx(i);
                  int childLeft = Math.round(glyphLeft - child.getEdgePaddingPx());
                  child.layout(childLeft, childTop, childLeft + childW, childTop + childH);
                  if (glyphLeft + child.getAdvanceWidthPx() >= contentRight + child.getEdgePaddingPx()) {
                     break;
                  }
               }
            }

         }
      }

      private float resolveGlyphRunLeftPx(int glyphIndex) {
         if (glyphIndex < 0 || glyphIndex >= this.glyphCodePointStart.length) {
            return 0.0F;
         }

         int codePointIndex = Math.max(0, Math.min(this.visibleCharCount - 1, this.glyphCodePointStart[glyphIndex]));
         return codePointIndex >= 0 && codePointIndex < this.codePointLeftPx.length ? this.codePointLeftPx[codePointIndex] : 0.0F;
      }

      private void applySolidVisualState() {
         if (this.currentUseHdrColor) {
            this.rowTextView.setTextColorLong(this.currentHdrColor);
         } else {
            this.rowTextView.setTextColorInt(this.currentSolidColor);
         }

         this.rowTextView.setBlurRadiusPx(this.currentBlurRadiusPx);
         this.rowTextView.setPresentationBlurRadiusPx(0.0F);
      }

      private void syncUniformBlurState() {
         if (!this.usingGlyphViews) {
            this.applyRowRenderEffectBlur(0.0F);
            this.appliedGlyphSoftwareBlurRadiusPx = Float.NaN;
            this.rowTextView.setBlurRadiusPx(this.currentBlurRadiusPx);
            this.rowTextView.setPresentationBlurRadiusPx(0.0F);
         } else {
            boolean useRowRenderEffectBlur = this.shouldUseRowRenderEffectBlur();
            this.applyUniformGlyphSoftwareBlurRadius(useRowRenderEffectBlur ? 0.0F : this.currentBlurRadiusPx);
            this.applyRowRenderEffectBlur(useRowRenderEffectBlur ? this.currentBlurRadiusPx : 0.0F);
         }
      }

      private boolean shouldUseRowRenderEffectBlur() {
         return VERSION.SDK_INT >= 31 && this.usingGlyphViews && !this.prepDotsMode && this.visibleGlyphCount > 0 && this.currentBlurRadiusPx > 0.01F;
      }

      private void applyUniformGlyphSoftwareBlurRadius(float blurRadiusPx) {
         if (!(Math.abs(this.appliedGlyphSoftwareBlurRadiusPx - blurRadiusPx) < 0.01F)) {
            this.appliedGlyphSoftwareBlurRadiusPx = blurRadiusPx;

            for(int i = 0; i < this.visibleGlyphCount && i < this.charViews.size(); ++i) {
               LyricGlyphRun glyph = (LyricGlyphRun)this.charViews.get(i);
               if (glyph.getVisibility() == 0) {
                  glyph.setBlurRadiusPx(blurRadiusPx);
               }
            }

         }
      }

      /**
       * A blurred row is a layer holding its blurred content. As a View RenderEffect the blur ran
       * again (on Skia GL) every time the row was drawn, and the lyrics are redrawn for every MV
       * frame and every frame of a karaoke line or a drag: a blur per row per frame. In the layer
       * it runs only when the row itself changes (its text, colour or radius).
       */
      private void applyRowRenderEffectBlur(float blurRadiusPx) {
         if (VERSION.SDK_INT >= 31) {
            if (!(Math.abs(this.appliedRowRenderBlurRadiusPx - blurRadiusPx) < 0.01F)) {
               this.appliedRowRenderBlurRadiusPx = blurRadiusPx;
               this.rowBlurEffect = resolveCachedRowBlurRenderEffect(blurRadiusPx);
               int layerType = this.rowBlurEffect != null ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
               if (this.getLayerType() != layerType) {
                  this.setLayerType(layerType, null);
               }
               this.invalidate();
            }
         }
      }

      @Nullable
      private static RenderEffect resolveCachedRowBlurRenderEffect(float blurRadiusPx) {
         if (VERSION.SDK_INT >= 31 && !(blurRadiusPx <= 0.01F)) {
            int key = Math.round(blurRadiusPx * 100.0F);
            synchronized(ROW_BLUR_EFFECT_CACHE_LOCK) {
               RenderEffect cached = (RenderEffect)ROW_BLUR_EFFECT_CACHE.get(key);
               if (cached != null) {
                  return cached;
               } else {
                  RenderEffect created = RenderEffect.createBlurEffect((float)key / 100.0F, (float)key / 100.0F, TileMode.CLAMP);
                  ROW_BLUR_EFFECT_CACHE.put(key, created);
                  return created;
               }
            }
         } else {
            return null;
         }
      }

      private void ensureCharViewCapacity(int count) {
         while(this.charViews.size() < count) {
            LyricGlyphRun glyph = new LyricGlyphRun(this, this.glyphPaints);
            glyph.setTextSizePx(this.charTextPx);
            glyph.setWeightStrengthPx(this.weightStrengthPx);
            glyph.setEdgePaddingPx(this.glyphEdgePaddingPx);
            glyph.setPresentationTextScale(this.contentPresentationScale);
            glyph.setVisibility(8);
            this.charViews.add(glyph);
         }

         this.applyGlyphWeightMode();
      }

      private void applyGlyphWeightMode() {
         float weight = this.prepDotsMode ? this.prepDotWeightStrengthPx : this.weightStrengthPx;

         for(int i = 0; i < this.charViews.size(); ++i) {
            ((LyricGlyphRun)this.charViews.get(i)).setWeightStrengthPx(weight);
         }

      }

      private float resolveDotLead(float revealPhase, int dotIndex) {
         if (dotIndex < 0) {
            return 0.0F;
         } else {
            int clampedIndex = Math.min(2, dotIndex);
            float segment = 0.33333334F;
            float start = (float)clampedIndex * segment;
            float end = start + segment;
            if (revealPhase <= start) {
               return 0.0F;
            } else if (revealPhase >= end) {
               return 1.0F;
            } else {
               float local = (revealPhase - start) / segment;
               return this.easeOut(local);
            }
         }
      }

      private float resolvePrepDotBlurRadiusPx(float lead, float rowScale, float finalVanishProgress) {
         float dotScaleProgress = this.clamp01(lead);
         float finalScaleProgress = 0.0F;
         if (rowScale > 1.0F) {
            finalScaleProgress = this.clamp01((rowScale - 1.0F) / 0.17F);
         }

         float blurDismissProgress = Math.max(dotScaleProgress, finalScaleProgress);
         float vanishBlurProgress = this.clamp01(finalVanishProgress);
         float rawBlurRadiusPx = this.prepDotRestBlurPx * ((1.0F - blurDismissProgress) + vanishBlurProgress * 0.85F);
         if (rawBlurRadiusPx <= 0.01F) {
            return 0.0F;
            } else {
               float quantum = Math.max(0.01F, this.blurRadiusQuantizePx);
               return (float)Math.round(rawBlurRadiusPx / quantum) * quantum;
            }
         }

      @NonNull
      private float[] ensureFloatCapacity(@NonNull float[] values, int requiredSize) {
         if (values.length >= requiredSize) {
            return values;
         } else {
            int capacity = Math.max(requiredSize, Math.max(8, values.length * 2));
            return new float[capacity];
         }
      }

      @NonNull
      private int[] ensureIntCapacity(@NonNull int[] values, int requiredSize) {
         if (values.length >= requiredSize) {
            return values;
         } else {
            int capacity = Math.max(requiredSize, Math.max(8, values.length * 2));
            return new int[capacity];
         }
      }

      @NonNull
      private long[] ensureLongCapacity(@NonNull long[] values, int requiredSize) {
         if (values.length >= requiredSize) {
            return values;
         } else {
            int capacity = Math.max(requiredSize, Math.max(8, values.length * 2));
            return new long[capacity];
         }
      }

      private float clamp01(float value) {
         if (value < 0.0F) {
            return 0.0F;
         } else {
            return value > 1.0F ? 1.0F : value;
         }
      }

      private float easeOut(float value) {
         float t = this.clamp01(value);
         float inv = 1.0F - t;
         return 1.0F - inv * inv * inv;
      }

      private float smootherStep(float value) {
         float t = this.clamp01(value);
         return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
      }

      private float lerp(float start, float end, float value) {
         return start + (end - start) * this.clamp01(value);
      }
   }

   public interface OnLyricSeekListener {
      void onLyricSeekRequested(long var1);
   }

   public interface OnRowsReadyListener {
      void onRowsReady();
   }

   public interface OnTranslationStateChangeListener {
      void onTranslationStateChanged(boolean var1, boolean var2);
   }
}
