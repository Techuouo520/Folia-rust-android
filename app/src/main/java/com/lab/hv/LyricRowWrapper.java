package com.lab.hv;

import android.text.TextPaint;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Worker-owned Android font metrics and the original language-aware wrapping rules.
 * No View reads or shared Paint mutation occurs while a page is animating. */
final class LyricRowWrapper {
   private final TextPaint paint;
   private final float maxWidthPx;
   private final float minimumWidthPx;

   LyricRowWrapper(TextPaint paint, float maxWidthPx, float minimumWidthPx) {
      this.paint = new TextPaint(paint);
      this.maxWidthPx = maxWidthPx;
      this.minimumWidthPx = minimumWidthPx;
   }

   private float measureTextPx(String text, float size) {
      paint.setTextSize(size);
      return paint.measureText(text);
   }

   @NonNull
   List<String> split(@Nullable String text, int charsPerRow, float textSizePx) {
      if (TextUtils.isEmpty(text)) {
         return Collections.emptyList();
      } else {
         int safeRowChars = Math.max(1, charsPerRow);
         if (this.shouldUseWordAwareWrap(text)) {
            return this.splitWordAwareRows(text, safeRowChars, textSizePx);
         } else {
            float maxWidthPx = this.maxWidthPx;
            return maxWidthPx <= this.minimumWidthPx ? this.splitByCodePointBudget(text, safeRowChars) : this.splitCodePointByWidth(text, maxWidthPx, textSizePx, safeRowChars);
         }
      }
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
         float maxWidthPx = this.maxWidthPx;
         if (maxWidthPx <= this.minimumWidthPx) {
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
   static String collapseSpaces(@NonNull String text) {
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
         if (!(maxWidthPx <= this.minimumWidthPx)) {
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
      } else if (maxWidthPx <= this.minimumWidthPx) {
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

}
